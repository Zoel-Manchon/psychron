// Host-side tests for the unconfirmed-publish window. Header only, so:
//
//   g++ -std=c++17 -Wall -Wextra -fsanitize=address,undefined
//       -I../psychron_node test_inflight.cpp -o test_inflight

#include "inflight.h"

#include <cstdio>
#include <string>
#include <vector>

static int failures = 0;

static void check(bool ok, const std::string &label, const std::string &detail = "") {
  printf("  %s  %s%s%s\n", ok ? "OK  " : "FAIL", label.c_str(),
         detail.empty() ? "" : " - ", detail.c_str());
  if (!ok) failures++;
}

static StoreRecord rec(uint32_t seq) {
  StoreRecord r{};
  r.boot = 7;
  r.seq = seq;
  return r;
}

static std::vector<uint32_t> reclaimSeqs(inflight::Window &w, uint32_t now) {
  std::vector<uint32_t> out;
  w.reclaim(now, [&](const StoreRecord &r) { out.push_back(r.seq); });
  return out;
}

static std::string join(const std::vector<uint32_t> &v) {
  std::string s;
  for (auto x : v) s += (s.empty() ? "" : ",") + std::to_string(x);
  return s;
}

// A window of 45 s, as the firmware configures it: 2 x 15 s keepalive + 15 s.
static const uint32_t HORIZON = 45000;

int main() {
  // Static: the ring is ~9 KB, sized for a global on the ESP32, not for a stack.
  static inflight::Window w(HORIZON);

  printf("unproven publishes come back, in order:\n");
  {
    for (uint32_t i = 1; i <= 5; i++) w.sent(rec(i), 1000 + i * 3000);
    check(w.size() == 5, "five publishes held");
    const auto got = reclaimSeqs(w, 20000);
    check(join(got) == "1,2,3,4,5", "reclaimed oldest first", join(got));
    check(w.size() == 0, "and the window is empty afterwards");
  }

  printf("\nproven publishes are forgotten:\n");
  {
    w.sent(rec(10), 0);
    w.sent(rec(11), 30000);
    w.sent(rec(12), 44000);
    // At 60 s, seq 10 is 60 s old (past the horizon); 11 is 30 s, 12 is 16 s.
    const auto got = reclaimSeqs(w, 60000);
    check(join(got) == "11,12", "only those inside the horizon", join(got));
  }
  {
    w.sent(rec(20), 0);
    const auto got = reclaimSeqs(w, HORIZON);
    check(join(got) == "20", "exactly at the horizon is still unproven", join(got));
    w.sent(rec(21), 0);
    check(reclaimSeqs(w, HORIZON + 1).empty(), "one millisecond past it is proven");
  }

  printf("\nmillis() wrapping at 49 days:\n");
  {
    const uint32_t nearWrap = 0xFFFFFFFFu - 10000;   // 10 s before the wrap
    w.sent(rec(30), nearWrap);
    w.sent(rec(31), 5000);                          // 15 s later, after the wrap
    const auto got = reclaimSeqs(w, 20000);         // 30 s after seq 30
    check(join(got) == "30,31", "an entry from before the wrap is still unproven", join(got));
    w.sent(rec(32), nearWrap);
    check(reclaimSeqs(w, 40000).empty(), "and expires on time across it");
  }

  printf("\na full window overwrites its oldest, and says so:\n");
  {
    const uint32_t before = w.overwritten();
    for (uint32_t i = 0; i < inflight::CAPACITY + 3; i++) w.sent(rec(100 + i), 1000);
    check(w.size() == inflight::CAPACITY, "size stays at capacity");
    check(w.overwritten() - before == 3, "three overwrites counted",
          std::to_string(w.overwritten() - before));
    check(w.room() == 0, "no room left");
    const auto got = reclaimSeqs(w, 2000);
    check(got.size() == inflight::CAPACITY && got.front() == 103 &&
          got.back() == 100 + inflight::CAPACITY + 2,
          "the survivors are the newest, in order",
          std::to_string(got.front()) + ".." + std::to_string(got.back()));
  }

  printf("\nexpiry frees room for the drain:\n");
  {
    for (uint32_t i = 0; i < 40; i++) w.sent(rec(i), 0);
    check(w.room() == inflight::CAPACITY - 40, "forty held");
    w.expire(HORIZON + 1);
    check(w.room() == inflight::CAPACITY, "all proven after the horizon");
  }

  printf("\nthe ring wraps without losing order:\n");
  {
    // Push the head round the ring several times with a mix of expiry and reclaim.
    uint32_t now = 0, seq = 1000;
    bool ordered = true;
    for (int round = 0; round < 20; round++) {
      for (int i = 0; i < 37; i++) w.sent(rec(seq++), now += 1500);
      if (round % 3 == 2) {
        const auto got = reclaimSeqs(w, now);
        for (size_t i = 1; i < got.size(); i++) ordered &= got[i] == got[i - 1] + 1;
        ordered &= !got.empty() && got.back() == seq - 1;
      }
    }
    check(ordered, "consecutive and ending at the latest after many wraps");
  }

  printf("\n%s\n", failures ? "FAILURES ABOVE" : "all inflight tests passed");
  return failures ? 1 : 0;
}
