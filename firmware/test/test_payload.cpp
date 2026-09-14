// Host-side tests for the contract v1 serialiser. Built with g++, not with the
// Arduino toolchain: this is the one unit whose failures are silent, so it is
// worth being able to run it without a board attached.
//
//   g++ -std=c++17 -Wall -Wextra -fsanitize=address,undefined
//       -I../psychron_node test_payload.cpp ../psychron_node/payload.cpp -o test_payload

#include "payload.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>

static int failures = 0;

static void check(bool ok, const std::string &label, const std::string &detail = "") {
  printf("  %s  %s%s%s\n", ok ? "OK  " : "FAIL", label.c_str(),
         detail.empty() ? "" : " - ", detail.c_str());
  if (!ok) failures++;
}

static bool contains(const char *hay, const char *needle) {
  return strstr(hay, needle) != nullptr;
}

// Braces and quotes balance, and the string is a single closed object. Not a
// full parser, but enough to catch a truncated or malformed payload.
static bool wellFormed(const char *s) {
  int depth = 0;
  bool inStr = false, escaped = false;
  for (const char *p = s; *p; p++) {
    if (inStr) {
      if (escaped)            escaped = false;
      else if (*p == '\\')    escaped = true;
      else if (*p == '"')     inStr = false;
      continue;
    }
    if (*p == '"')      inStr = true;
    else if (*p == '{') depth++;
    else if (*p == '}') { depth--; if (depth < 0) return false; }
  }
  return depth == 0 && !inStr && s[0] == '{';
}

static const NodeIdentity ID = {"esp32-01", "1.0.0", 1};

static StoreRecord sample() {
  StoreRecord r{};
  r.boot = 2748109371u;
  r.seq = 1234;
  r.uptime_ms = 2468000;
  r.ts = 1789012345;
  r.ts_ms = 437;
  r.temperature_c = 23.42f;
  r.humidity_pct = 51.20f;
  r.quality = 0;
  return r;
}

int main() {
  char buf[512];

  printf("contract v1 reading payload:\n");
  {
    const StoreRecord r = sample();
    const size_t n = buildReadingPayload(buf, sizeof(buf), ID, r);
    check(n > 0, "a normal reading serialises");
    check(n == strlen(buf), "returned length matches the string");
    check(wellFormed(buf), "well formed JSON object", buf);
    check(contains(buf, "\"dev\":\"esp32-01\""), "carries the device id");
    check(contains(buf, "\"boot\":2748109371"), "carries the boot id");
    check(contains(buf, "\"seq\":1234"), "carries the sequence");
    check(contains(buf, "\"ts\":1789012345,\"ms\":437"), "carries the device clock to the millisecond");
    check(contains(buf, "\"up\":2468000"), "carries uptime");
    check(contains(buf, "\"t\":23.42"), "temperature to two decimals");
    check(contains(buf, "\"h\":51.20"), "humidity to two decimals");
  }

  printf("\nunknown values are null, never a substitute:\n");
  {
    StoreRecord r = sample();
    r.ts = 0;                                  // clock never synced
    buildReadingPayload(buf, sizeof(buf), ID, r);
    check(contains(buf, "\"ts\":null"), "unsynced clock emits null");
    check(!contains(buf, "\"ts\":0"), "and never emits epoch zero");
    check(!contains(buf, "\"ms\""), "and no milliseconds of an unknown second");
  }
  {
    StoreRecord r = sample();
    r.temperature_c = NAN;
    r.humidity_pct = NAN;
    buildReadingPayload(buf, sizeof(buf), ID, r);
    check(contains(buf, "\"t\":null"), "failed read emits null temperature");
    check(contains(buf, "\"h\":null"), "failed read emits null humidity");
    check(!contains(buf, "nan"), "never leaks a printf nan into the wire format");
    check(wellFormed(buf), "still well formed with nulls", buf);
  }

  printf("\nmilliseconds:\n");
  {
    StoreRecord r = sample();
    r.ts_ms = 0;
    buildReadingPayload(buf, sizeof(buf), ID, r);
    check(contains(buf, "\"ms\":0,"), "the first millisecond of a second is still sent");
    r.ts_ms = 999;
    buildReadingPayload(buf, sizeof(buf), ID, r);
    check(contains(buf, "\"ms\":999,"), "and the last");
    r.ts_ms = 1000;
    buildReadingPayload(buf, sizeof(buf), ID, r);
    check(!contains(buf, "\"ms\""), "an impossible millisecond is omitted, not sent");
    check(contains(buf, "\"ts\":1789012345,\"up\""), "leaving the reading correct to the second");
    check(wellFormed(buf), "and well formed", buf);
  }

  printf("\nquality flags survive:\n");
  {
    StoreRecord r = sample();
    r.quality = 0x01 | 0x02 | 0x10;            // unsynced | replayed | drift
    buildReadingPayload(buf, sizeof(buf), ID, r);
    check(contains(buf, "\"q\":19"), "bitfield serialised as an integer");
  }

  printf("\ntruncation is reported, not silently emitted:\n");
  {
    const StoreRecord r = sample();
    const size_t full = buildReadingPayload(buf, sizeof(buf), ID, r);

    for (size_t cap = 1; cap < full + 2; cap++) {
      char small[256];
      memset(small, 0x7E, sizeof(small));
      const size_t n = buildReadingPayload(small, cap, ID, r);

      if (cap <= full) {
        if (n != 0) {
          check(false, "short buffer must report failure",
                "cap=" + std::to_string(cap) + " returned " + std::to_string(n));
          break;
        }
        if (strlen(small) >= cap) {
          check(false, "wrote past the buffer", "cap=" + std::to_string(cap));
          break;
        }
        // Nothing beyond the buffer may have been touched.
        if (small[cap] != 0x7E) {
          check(false, "clobbered memory past the buffer", "cap=" + std::to_string(cap));
          break;
        }
      }
    }
    check(true, "every buffer size from 1 to " + std::to_string(full + 1) + " stays in bounds");
    check(buildReadingPayload(buf, 0, ID, r) == 0, "zero-length buffer refuses");
    check(buildReadingPayload(nullptr, 10, ID, r) == 0, "null buffer refuses");
  }

  printf("\nboot payload:\n");
  {
    const size_t n = buildBootPayload(buf, sizeof(buf), ID, 42, "brownout", -67, 12, 3, true);
    check(n > 0 && wellFormed(buf), "serialises", buf);
    check(contains(buf, "\"reset\":\"brownout\""), "carries the reset reason");
    check(contains(buf, "\"rssi\":-67"), "carries a negative rssi");
    check(contains(buf, "\"store_ok\":true"), "booleans are JSON booleans");
  }

  printf("\n%s\n", failures ? "FAILURES ABOVE" : "all payload tests passed");
  return failures ? 1 : 0;
}
