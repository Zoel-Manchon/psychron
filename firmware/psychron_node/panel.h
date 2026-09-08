#pragma once
#include <Arduino.h>
#include "sensor.h"

// The SSD1306 view. Layout is the "two symmetric columns" variant from the
// Claude Design handoff: temperature and humidity carry equal weight because the
// DHT22 reports two measurements and the panel does not choose between them.

namespace panel {

bool begin();                                  // false if the OLED never answered
void banner(const char *l1, const char *l2);
void pushTrend(float temperature_c);
void reading(const Reading &r, uint32_t seq, const char *footer);
void fault(const char *l1, const char *l2, const char *footer);

}  // namespace panel
