#pragma once
#include <stdint.h>
#include <stddef.h>

// A fixed-size ring of readings on the flash filesystem, used only while the
// link is down. Records are fixed width so a slot is a seek, not a scan, and
// each one carries a CRC because a power cut mid-write leaves a torn record
// that would otherwise be replayed as a plausible measurement.

struct StoreRecord {
  uint32_t boot;
  uint32_t seq;
  uint32_t uptime_ms;
  int32_t  ts;              // epoch seconds; 0 means the clock was not synced
  uint16_t ts_ms;           // milliseconds within ts, 0-999; meaningless when ts is 0
  uint16_t reserved;        // keeps the floats on a 4-byte boundary with no padding
  float    temperature_c;
  float    humidity_pct;
  uint16_t quality;
  uint16_t crc;
};

namespace store {

bool     begin();
// persist=false leaves the header for flush(): for a batch of pushes, when losing
// the batch to a power cut costs less than a header commit per record.
bool     push(const StoreRecord &r, bool persist = true);
bool     peek(StoreRecord &r);        // false when empty or the head is corrupt
// Moves the tail in memory only. A power cut before flush() replays the records
// popped since, which ingestion discards as duplicates: a repeat, not a loss.
void     pop();
void     flush();
uint32_t count();
uint32_t dropped();                   // records evicted because the ring filled
bool     healthy();                   // false if the filesystem never mounted

}  // namespace store
