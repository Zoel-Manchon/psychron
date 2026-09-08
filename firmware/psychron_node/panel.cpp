#include "panel.h"
#include "config.h"

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

namespace {

Adafruit_SSD1306 oled(SCREEN_W, SCREEN_H, &Wire, OLED_RESET);
bool ready = false;

float   trend[TREND_BARS];
uint8_t trendCount = 0;
uint8_t trendHead  = 0;

uint8_t trendFirst() {
  return (trendHead + TREND_BARS - trendCount) % TREND_BARS;
}

float trendDelta() {
  if (trendCount < 2) return 0.0f;
  return trend[(trendHead + TREND_BARS - 1) % TREND_BARS] - trend[trendFirst()];
}

// The label is derived from the sampling constants rather than written down, so
// it cannot drift out of step with the window it actually describes.
void trendLabel(char *buf, size_t n) {
  const uint32_t windowS = (uint32_t)TREND_BARS * TREND_PERIOD_MS / 1000UL;
  if (windowS >= 120) snprintf(buf, n, "%lu MIN", (unsigned long)(windowS / 60));
  else                snprintf(buf, n, "%lu S", (unsigned long)windowS);
}

// Bars quantise better than a polyline at one bit per pixel, and they sit on the
// same 6 px pitch as the character grid.
void drawTrend(int16_t x0, int16_t yBottom, int16_t bw, int16_t gap, int16_t maxH) {
  if (trendCount == 0) return;
  const uint8_t first = trendFirst();

  float lo = trend[first], hi = trend[first];
  for (uint8_t i = 0; i < trendCount; i++) {
    const float v = trend[(first + i) % TREND_BARS];
    if (v < lo) lo = v;
    if (v > hi) hi = v;
  }
  float span = hi - lo;
  if (span <= 0.0f) span = 1.0f;      // a flat window draws a flat floor

  for (uint8_t i = 0; i < trendCount; i++) {
    const float v = trend[(first + i) % TREND_BARS];
    const int16_t h = 1 + (int16_t)((v - lo) / span * (maxH - 1) + 0.5f);
    oled.fillRect(x0 + i * (bw + gap), yBottom - h + 1, bw, h, SSD1306_WHITE);
  }
}

// Each column is 63 px, so a value gets four characters at size 2. Drop the
// decimal rather than let a reading run across the divider.
void fmtColumn(char *buf, size_t n, float v) {
  snprintf(buf, n, "%.1f", v);
  if (strlen(buf) > 4) snprintf(buf, n, "%.0f", v);
}

void drawFooter(const char *footer, uint32_t seq) {
  oled.setTextSize(1);
  oled.setCursor(0, 56);
  oled.print(footer);
  // A block that flips filled/hollow each refresh: a frozen loop is otherwise
  // indistinguishable from a room whose temperature simply is not changing.
  if (seq & 1) oled.fillRect(122, 57, 5, 5, SSD1306_WHITE);
  else         oled.drawRect(122, 57, 5, 5, SSD1306_WHITE);
}

}  // namespace

namespace panel {

bool begin() {
  Wire.begin(I2C_SDA, I2C_SCL, I2C_HZ);

  // periphBegin=false so the driver keeps the Wire pins configured above.
  for (uint8_t addr : {0x3C, 0x3D}) {
    if (oled.begin(SSD1306_SWITCHCAPVCC, addr, true, false)) {
      ready = true;
      oled.cp437(true);
      break;
    }
  }
  return ready;
}

void banner(const char *l1, const char *l2) {
  if (!ready) return;
  oled.clearDisplay();
  oled.setTextSize(1);
  oled.setTextColor(SSD1306_WHITE);
  oled.setCursor(0, 0);
  oled.println(l1);
  oled.println(l2);
  oled.display();
}

void pushTrend(float temperature_c) {
  trend[trendHead] = temperature_c;
  trendHead = (trendHead + 1) % TREND_BARS;
  if (trendCount < TREND_BARS) trendCount++;
}

void reading(const Reading &r, uint32_t seq, const char *footer) {
  if (!ready) return;
  char buf[24];

  oled.clearDisplay();
  oled.setTextColor(SSD1306_WHITE);

  oled.setTextSize(1);
  oled.setCursor(0, 0);
  oled.print(F("TEMP"));
  oled.setCursor(66, 0);
  oled.print(F("HUM"));

  oled.setTextSize(2);
  fmtColumn(buf, sizeof(buf), r.temperature_c);
  oled.setCursor(0, 8);
  oled.print(buf);
  fmtColumn(buf, sizeof(buf), r.humidity_pct);
  oled.setCursor(66, 8);
  oled.print(buf);

  oled.setTextSize(1);
  oled.setCursor(50, 16);
  oled.write(248);                   // degree sign in true CP437
  oled.print(F("C"));
  oled.setCursor(114, 16);
  oled.print(F("%"));

  const float delta = trendDelta();
  oled.setCursor(0, 24);
  oled.write(delta > 0.35f ? 24 : (delta < -0.35f ? 25 : '='));   // CP437 arrows
  snprintf(buf, sizeof(buf), " %+.1f", delta);
  oled.print(buf);

  trendLabel(buf, sizeof(buf));
  oled.setCursor(66, 24);
  oled.print(buf);

  oled.drawFastVLine(63, 0, 31, SSD1306_WHITE);
  oled.drawFastHLine(0, 31, SCREEN_W, SSD1306_WHITE);

  oled.setCursor(0, 32);
  oled.print(F("HEAT INDEX"));
  snprintf(buf, sizeof(buf), "%.1f", r.heat_index_c);
  oled.setCursor(96, 32);
  oled.print(buf);
  oled.drawFastHLine(0, 39, SCREEN_W, SSD1306_WHITE);

  drawTrend(0, 54, 5, 1, 15);
  oled.drawFastHLine(0, 55, SCREEN_W, SSD1306_WHITE);

  drawFooter(footer, seq);
  oled.display();
}

void fault(const char *l1, const char *l2, const char *footer) {
  if (!ready) return;
  oled.clearDisplay();
  oled.setTextSize(1);
  oled.setTextColor(SSD1306_WHITE);
  oled.setCursor(0, 0);
  oled.println(l1);
  oled.println(l2);
  oled.drawFastHLine(0, 55, SCREEN_W, SSD1306_WHITE);
  drawFooter(footer, 0);
  oled.display();
}

}  // namespace panel
