#include <Arduino.h>
#include "store.h"
#include "config.h"
#include <LittleFS.h>

namespace {

struct Header {
  uint32_t magic;
  uint32_t head;      // next slot to write
  uint32_t tail;      // next slot to read
  uint32_t count;
};

// "PSQ2". Bumped with the record layout: a ring written in the 28-byte format and
// read as 32-byte records would replay garbage whose CRCs happen to be checked
// against the wrong span. A new magic makes begin() recreate the file instead. The
// cost is any backlog buffered at the moment of the update, and an update arrives
// over the network, which means the link — and so the backlog — was up and empty.
const uint32_t MAGIC = 0x50535132;

Header   hdr     = {MAGIC, 0, 0, 0};
bool     mounted = false;
uint32_t evicted = 0;

static_assert(sizeof(StoreRecord) == 32, "record layout must stay fixed on disk");

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

size_t slotOffset(uint32_t index) {
  return sizeof(Header) + (size_t)(index % STORE_CAPACITY) * sizeof(StoreRecord);
}

bool writeHeader() {
  File f = LittleFS.open(STORE_PATH, "r+");
  if (!f) return false;
  f.seek(0);
  const bool ok = f.write(reinterpret_cast<uint8_t *>(&hdr), sizeof(hdr)) == sizeof(hdr);
  f.close();
  return ok;
}

// Creates the file at full size so a later write can never fail for lack of
// space — discovering the filesystem is full halfway through an outage would
// lose exactly the readings the buffer exists to keep.
bool createFile() {
  File f = LittleFS.open(STORE_PATH, "w");
  if (!f) return false;

  hdr = {MAGIC, 0, 0, 0};
  f.write(reinterpret_cast<uint8_t *>(&hdr), sizeof(hdr));

  StoreRecord blank = {};
  for (uint32_t i = 0; i < STORE_CAPACITY; i++) {
    if (f.write(reinterpret_cast<uint8_t *>(&blank), sizeof(blank)) != sizeof(blank)) {
      f.close();
      LittleFS.remove(STORE_PATH);
      return false;
    }
  }
  f.close();
  return true;
}

}  // namespace

namespace store {

bool begin() {
  if (!LittleFS.begin(true)) {          // true: format if the mount fails
    mounted = false;
    return false;
  }

  File f = LittleFS.open(STORE_PATH, "r");
  bool needsCreate = !f || f.size() < sizeof(Header) + (size_t)STORE_CAPACITY * sizeof(StoreRecord);

  if (!needsCreate) {
    f.read(reinterpret_cast<uint8_t *>(&hdr), sizeof(hdr));
    // A header that does not validate means a different build, a different
    // capacity, or a torn write. Starting clean loses buffered readings; trusting
    // it would replay whatever the bytes happened to spell.
    needsCreate = hdr.magic != MAGIC || hdr.count > STORE_CAPACITY ||
                  hdr.head >= STORE_CAPACITY || hdr.tail >= STORE_CAPACITY;
  }
  if (f) f.close();

  mounted = needsCreate ? createFile() : true;
  return mounted;
}

bool push(const StoreRecord &r) {
  if (!mounted) return false;

  StoreRecord rec = r;
  rec.crc = recordCrc(rec);

  File f = LittleFS.open(STORE_PATH, "r+");
  if (!f) return false;
  f.seek(slotOffset(hdr.head));
  const bool ok = f.write(reinterpret_cast<uint8_t *>(&rec), sizeof(rec)) == sizeof(rec);
  f.close();
  if (!ok) return false;

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
  return writeHeader();
}

bool peek(StoreRecord &out) {
  if (!mounted || hdr.count == 0) return false;

  File f = LittleFS.open(STORE_PATH, "r");
  if (!f) return false;

  // Walk past torn records rather than stalling the drain on one bad slot.
  uint32_t skipped = 0;
  while (hdr.count > 0 && skipped < STORE_CAPACITY) {
    f.seek(slotOffset(hdr.tail));
    if (f.read(reinterpret_cast<uint8_t *>(&out), sizeof(out)) != sizeof(out)) break;

    if (out.crc == recordCrc(out)) {
      f.close();
      return true;
    }
    hdr.tail = (hdr.tail + 1) % STORE_CAPACITY;
    hdr.count--;
    evicted++;
    skipped++;
  }

  f.close();
  if (skipped) writeHeader();
  return false;
}

void pop() {
  if (!mounted || hdr.count == 0) return;
  hdr.tail = (hdr.tail + 1) % STORE_CAPACITY;
  hdr.count--;
  writeHeader();
}

uint32_t count()   { return mounted ? hdr.count : 0; }
uint32_t dropped() { return evicted; }
bool     healthy() { return mounted; }

}  // namespace store
