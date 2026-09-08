#pragma once
#include <Arduino.h>

// The DHT22 reader. Bring-up diagnostics — pin scanning, protocol probing,
// handshake tracing — deliberately live in the esp32-dht22-oled bench rig, not
// here: this firmware runs against wiring already known to work, and its job is
// to be dependable in operation rather than helpful during assembly.

struct Reading {
  bool     ok;
  float    temperature_c;
  float    humidity_pct;
  float    heat_index_c;
  uint16_t quality;        // Q_* flags from config.h
};

namespace sensor {

void    begin();
Reading read();

}  // namespace sensor
