#pragma once
#include <stddef.h>
#include <stdint.h>

#include "store.h"

// Readings the MQTT client accepted but the broker is not yet known to have.
//
// PubSubClient publishes at QoS 0: publish() returns true once the bytes are in
// the TCP send buffer, not once the broker has read them. When the path to the
// broker dies without a reset reaching the node, every reading "sent" until the
// keepalive notices is gone. Measured: three network flaps on the host lost 45
// readings, each one counted as published.
//
// So each publish is remembered here until it is provably delivered, and handed
// back to the store if the session it went out on ends first. Ingestion already
// discards a repeated (device, boot, seq), so a reading sent twice costs a row
// lookup and a reading sent zero times costs the reading.
//
// Pure C++ with the clock passed in, so the host tests exercise it directly.

namespace inflight {

// 256 records is about 9 KB of RAM. Live traffic needs a fraction of it; the rest
// is what bounds replay speed after an outage, since the drain waits for room.
constexpr size_t CAPACITY = 256;

class Window {
 public:
  // horizonMs: how long a publish stays unproven. The broker answers a keepalive
  // only after reading everything sent before it on the same stream, and a dead
  // stream is noticed within two keepalive intervals, so anything older than that
  // plus a margin for TCP retransmission has either arrived or been noticed lost.
  explicit Window(uint32_t horizonMs) : horizon_(horizonMs) {}

  // A publish the client accepted. When full, the oldest entry is overwritten
  // and counted: it has lost its protection, and that is not silent.
  void sent(const StoreRecord &r, uint32_t nowMs) {
    expire(nowMs);
    if (count_ == CAPACITY) {
      head_ = (head_ + 1) % CAPACITY;
      count_--;
      overwritten_++;
    }
    ring_[(head_ + count_) % CAPACITY] = Entry{r, nowMs};
    count_++;
  }

  // Forgets entries old enough to be proven delivered. Oldest first, so it stops
  // at the first entry still inside the horizon.
  void expire(uint32_t nowMs) {
    // Unsigned subtraction, so the comparison survives millis() wrapping at 49 days.
    while (count_ > 0 && nowMs - ring_[head_].at > horizon_) {
      head_ = (head_ + 1) % CAPACITY;
      count_--;
    }
  }

  // The session these went out on has ended: every entry still unproven is handed
  // to `take`, oldest first, and the window empties. Returns how many were handed.
  template <typename F>
  size_t reclaim(uint32_t nowMs, F &&take) {
    expire(nowMs);
    const size_t n = count_;
    for (size_t i = 0; i < n; i++) take(ring_[(head_ + i) % CAPACITY].record);
    head_ = 0;
    count_ = 0;
    return n;
  }

  size_t   size() const { return count_; }
  size_t   room() const { return CAPACITY - count_; }
  uint32_t overwritten() const { return overwritten_; }

 private:
  struct Entry {
    StoreRecord record;
    uint32_t    at;
  };

  Entry    ring_[CAPACITY];
  size_t   head_ = 0;
  size_t   count_ = 0;
  uint32_t horizon_;
  uint32_t overwritten_ = 0;
};

}  // namespace inflight
