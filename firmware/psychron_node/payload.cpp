#include "payload.h"

#include <math.h>
#include <stdarg.h>
#include <stdio.h>

namespace {

// snprintf returns the length it *would* have written, so the obvious
// `at += snprintf(buf + at, n - at, ...)` lets `at` run past `n`; the next call
// then computes `n - at` in unsigned arithmetic and passes a huge size to
// snprintf, which is a buffer overflow rather than a truncation. This wrapper
// stops at the first overrun and reports it.
struct Cursor {
  char  *buf;
  size_t cap;
  size_t at        = 0;
  bool   truncated = false;

  void addf(const char *fmt, ...) {
    if (truncated || at >= cap) { truncated = true; return; }
    va_list ap;
    va_start(ap, fmt);
    const int w = vsnprintf(buf + at, cap - at, fmt, ap);
    va_end(ap);
    if (w < 0 || (size_t)w >= cap - at) { truncated = true; at = cap - 1; return; }
    at += (size_t)w;
  }

  // A value that is not known is emitted as null, never as a plausible
  // substitute: a failed read writing 0.0 records a lie no analysis can detect.
  void addOptFloat(const char *key, float v) {
    if (isnan(v)) addf(",\"%s\":null", key);
    else          addf(",\"%s\":%.2f", key, v);
  }
};

}  // namespace

size_t buildReadingPayload(char *buf, size_t n, const NodeIdentity &id,
                           const StoreRecord &r) {
  if (!buf || n == 0) return 0;

  Cursor c{buf, n};
  c.addf("{\"v\":%d,\"dev\":\"%s\",\"fw\":\"%s\",\"boot\":%u,\"seq\":%u",
         id.contract_version, id.device_id, id.firmware,
         (unsigned)r.boot, (unsigned)r.seq);

  if (r.ts > 0) {
    c.addf(",\"ts\":%ld", (long)r.ts);
    // Only a millisecond that can be one. A record read back from flash has
    // passed its CRC, but an out-of-range value would get the whole reading
    // rejected; omitting the field still leaves it correct to the second.
    if (r.ts_ms <= 999) c.addf(",\"ms\":%u", (unsigned)r.ts_ms);
  } else {
    c.addf(",\"ts\":null");
  }

  c.addf(",\"up\":%u", (unsigned)r.uptime_ms);
  c.addOptFloat("t", r.temperature_c);
  c.addOptFloat("h", r.humidity_pct);
  c.addf(",\"q\":%u}", (unsigned)r.quality);

  return c.truncated ? 0 : c.at;
}

size_t buildBootPayload(char *buf, size_t n, const NodeIdentity &id,
                        uint32_t boot, const char *reset_reason, int rssi,
                        uint32_t queued, uint32_t dropped, bool store_ok) {
  if (!buf || n == 0) return 0;

  Cursor c{buf, n};
  c.addf("{\"v\":%d,\"dev\":\"%s\",\"fw\":\"%s\",\"boot\":%u,\"reset\":\"%s\"",
         id.contract_version, id.device_id, id.firmware, (unsigned)boot, reset_reason);
  c.addf(",\"rssi\":%d,\"queued\":%u,\"dropped\":%u,\"store_ok\":%s}",
         rssi, (unsigned)queued, (unsigned)dropped, store_ok ? "true" : "false");

  return c.truncated ? 0 : c.at;
}
