#pragma once
#include <Arduino.h>

// WiFi, clock and broker, maintained without ever blocking the read loop: a
// reconnect that stalls the loop drops readings, and a reading missed while the
// link was down is exactly the reading the offline store exists to keep.

enum class LinkState : uint8_t { Down, WifiOnly, Up };

namespace netlink {

void        begin();
void        loop();                 // call every pass; returns immediately
LinkState   state();
const char *stateLabel();           // short label for the panel footer
bool        clockSynced();
int         rssi();
const char *resetReason();

bool publish(const char *topicSuffix, const char *payload, bool retain = false);

}  // namespace netlink
