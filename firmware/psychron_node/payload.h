#pragma once
#include <stddef.h>
#include "store.h"

// Contract v1 serialisation, deliberately free of Arduino and of the config
// macros: it takes its identity as an argument so it can be compiled and tested
// on a host. This is the one piece whose bugs are invisible — a malformed or
// silently truncated payload is rejected downstream long after the reading it
// described has gone.

struct NodeIdentity {
  const char *device_id;
  const char *firmware;
  int         contract_version;
};

// Returns the number of bytes written, or 0 if the payload did not fit, in which
// case buf holds a truncated string that must not be sent.
size_t buildReadingPayload(char *buf, size_t n, const NodeIdentity &id,
                           const StoreRecord &r);

size_t buildBootPayload(char *buf, size_t n, const NodeIdentity &id,
                        uint32_t boot, const char *reset_reason, int rssi,
                        uint32_t queued, uint32_t dropped, bool store_ok);
