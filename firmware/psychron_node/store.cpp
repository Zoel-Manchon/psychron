#include <Arduino.h>
#include "store.h"
#include "config.h"
#include <LittleFS.h>

// Layout, and why it is not one file.
//
// The ring used to be a single 160 KB file with its header at offset 0. LittleFS
// stores a file as a backwards-linked chain of blocks, so changing one block means
// rewriting it and every block after it — and the header sat in the first. Every
// push and every pop rewrote the whole file: about two seconds each, measured over
// the serial log. With a backlog, the loop stalled long enough for the MQTT
// keepalive to end the session, the unconfirmed publishes went back into the ring
// at two seconds apiece, the next session stalled the same way, and the node spent
// the night reconnecting with a backlog that grew instead of draining.
//
// Now the ring is forty files of exactly one flash block — 128 records of 32 bytes
// is 4096 — and the header is a file of its own, small enough that LittleFS keeps it
// inside the directory's metadata. A push rewrites one block and commits the header;
// a pop only moves the tail in memory, and flush() commits it once per batch.

namespace {

struct Header {
  uint32_t magic;
  uint32_t head;      // next slot to write
  uint32_t tail;      // next slot to read
  uint32_t count;
};

// "PSQ3": the split layout. "PSQ2" was the single file, whose backlog begin()
// carries across rather than discarding: it is exactly the data a node rebooted
// into this version during an outage would otherwise lose.
const uint32_t MAGIC = 0x50535133;
const uint32_t LEGACY_MAGIC = 0x50535132;
const char *const DIR = "/ring";
const char *const HEADER_PATH = "/ring/header";

constexpr uint32_t PER_FILE = 128;
constexpr uint32_t FILES = (STORE_CAPACITY + PER_FILE - 1) / PER_FILE;

Header   hdr     = {MAGIC, 0, 0, 0};
bool     mounted = false;
bool     dirty   = false;
uint32_t evicted = 0;

static_assert(sizeof(StoreRecord) == 32, "record layout must stay fixed on disk");
static_assert(PER_FILE * sizeof(StoreRecord) == 4096, "one ring file must be one flash block");

uint16_t crc16(const uint8_t *data, size_t n) {
  uint16_t crc = 0xFFFF;
  for (size_t i = 0; i < n; i++) {
    crc ^= (uint16_t)data[i] << 8;
    for (uint8_t b = 0; b < 8; b++) {
      crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
    }
  }
  return crc;
}

// Everything but the trailing crc field.
uint16_t recordCrc(const StoreRecord &r) {
  return crc16(reinterpret_cast<const uint8_t *>(&r),
               sizeof(StoreRecord) - sizeof(uint16_t));
}

void filePath(uint32_t file, char *out, size_t n) {
  snprintf(out, n, "%s/%02u", DIR, (unsigned)file);
}

bool writeHeader() {
  File f = LittleFS.open(HEADER_PATH, "w");
  if (!f) return false;
  const bool ok = f.write(reinterpret_cast<uint8_t *>(&hdr), sizeof(hdr)) == sizeof(hdr);
  f.close();
  if (ok) dirty = false;
  return ok;
}

bool writeSlot(uint32_t index, const StoreRecord &rec) {
  char path[24];
  filePath(index / PER_FILE, path, sizeof(path));
  File f = LittleFS.open(path, "r+");
  if (!f) return false;
  f.seek((index % PER_FILE) * sizeof(StoreRecord));
  const bool ok = f.write(reinterpret_cast<const uint8_t *>(&rec), sizeof(rec)) == sizeof(rec);
  f.close();
  return ok;
}

bool readSlot(uint32_t index, StoreRecord &out) {
  char path[24];
  filePath(index / PER_FILE, path, sizeof(path));
  File f = LittleFS.open(path, "r");
  if (!f) return false;
  f.seek((index % PER_FILE) * sizeof(StoreRecord));
  const bool ok = f.read(reinterpret_cast<uint8_t *>(&out), sizeof(out)) == sizeof(out);
  f.close();
  return ok;
}

// Every file written whole, once, at full size: a write can then never fail for
// lack of space in the middle of an outage, which is when it would matter.
bool createRing() {
  LittleFS.mkdir(DIR);
  static uint8_t block[PER_FILE * sizeof(StoreRecord)];
  memset(block, 0, sizeof(block));
  char path[24];
  for (uint32_t i = 0; i < FILES; i++) {
    filePath(i, path, sizeof(path));
    File f = LittleFS.open(path, "w");
    if (!f) return false;
    const bool ok = f.write(block, sizeof(block)) == sizeof(block);
    f.close();
    if (!ok) return false;
  }
  hdr = {MAGIC, 0, 0, 0};
  return writeHeader();
}

// The single-file ring of the previous layout, read in order and written into the
// new one block at a time — reads were never the slow part.
void migrateLegacy() {
  File f = LittleFS.open(STORE_PATH, "r");
  if (!f) return;
  Header old;
  const bool valid = f.read(reinterpret_cast<uint8_t *>(&old), sizeof(old)) == sizeof(old) &&
                     old.magic == LEGACY_MAGIC && old.count <= STORE_CAPACITY &&
                     old.tail < STORE_CAPACITY;
  uint32_t carried = 0;
  if (valid) {
    static uint8_t block[PER_FILE * sizeof(StoreRecord)];
    StoreRecord r;
    for (uint32_t i = 0; i < old.count; i++) {
      const uint32_t slot = (old.tail + i) % STORE_CAPACITY;
      f.seek(sizeof(Header) + (size_t)slot * sizeof(StoreRecord));
      if (f.read(reinterpret_cast<uint8_t *>(&r), sizeof(r)) != sizeof(r)) break;
      memcpy(block + (carried % PER_FILE) * sizeof(StoreRecord), &r, sizeof(r));
      carried++;
      if (carried % PER_FILE == 0 || i + 1 == old.count) {
        char path[24];
        filePath((carried - 1) / PER_FILE, path, sizeof(path));
        File out = LittleFS.open(path, "w");
        if (out) { out.write(block, sizeof(block)); out.close(); }
        memset(block, 0, sizeof(block));
      }
    }
  }
  f.close();
  LittleFS.remove(STORE_PATH);
  if (carried) {
    hdr = {MAGIC, carried % STORE_CAPACITY, 0, carried};
    writeHeader();
    Serial.printf("STORE;migrated=%u\n", (unsigned)carried);
  }
}

}  // namespace

namespace store {

bool begin() {
  if (!LittleFS.begin(true)) {          // true: format if the mount fails
    mounted = false;
    return false;
  }

  bool needsCreate = true;
  File f = LittleFS.open(HEADER_PATH, "r");
  if (f) {
    // A header that does not validate means a different build, a different
    // capacity, or a torn write. Starting clean loses buffered readings; trusting
    // it would replay whatever the bytes happened to spell.
    needsCreate = f.read(reinterpret_cast<uint8_t *>(&hdr), sizeof(hdr)) != sizeof(hdr) ||
                  hdr.magic != MAGIC || hdr.count > STORE_CAPACITY ||
                  hdr.head >= STORE_CAPACITY || hdr.tail >= STORE_CAPACITY;
    f.close();
  }
  if (needsCreate) {
    mounted = createRing();
    if (mounted) migrateLegacy();
  } else {
    mounted = true;
  }
  return mounted;
}

bool push(const StoreRecord &r, bool persist) {
  if (!mounted) return false;

  StoreRecord rec = r;
  rec.crc = recordCrc(rec);
  if (!writeSlot(hdr.head, rec)) return false;

  hdr.head = (hdr.head + 1) % STORE_CAPACITY;
  if (hdr.count == STORE_CAPACITY) {
    // Full: evict the oldest rather than refuse the newest. A long outage should
    // cost the start of the gap, not the readings closest to recovery. The count
    // is published so the loss shows up in the data instead of being silent.
    hdr.tail = (hdr.tail + 1) % STORE_CAPACITY;
    evicted++;
  } else {
    hdr.count++;
  }
  dirty = true;
  return persist ? writeHeader() : true;
}

bool peek(StoreRecord &out) {
  if (!mounted || hdr.count == 0) return false;

  // Walk past torn records rather than stalling the drain on one bad slot.
  uint32_t skipped = 0;
  while (hdr.count > 0 && skipped < STORE_CAPACITY) {
    if (!readSlot(hdr.tail, out)) break;
    if (out.crc == recordCrc(out)) {
      if (skipped) writeHeader();
      return true;
    }
    hdr.tail = (hdr.tail + 1) % STORE_CAPACITY;
    hdr.count--;
    evicted++;
    skipped++;
  }
  if (skipped) writeHeader();
  return false;
}

void pop() {
  if (!mounted || hdr.count == 0) return;
  hdr.tail = (hdr.tail + 1) % STORE_CAPACITY;
  hdr.count--;
  dirty = true;
}

void flush() {
  if (mounted && dirty) writeHeader();
}

uint32_t count()   { return mounted ? hdr.count : 0; }
uint32_t dropped() { return evicted; }
bool     healthy() { return mounted; }

}  // namespace store
