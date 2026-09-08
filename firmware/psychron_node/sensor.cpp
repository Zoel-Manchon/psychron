#include "sensor.h"
#include "config.h"
#include <DHT.h>

namespace {
DHT dht(DHT_PIN, DHT_TYPE);
}

namespace sensor {

void begin() {
  dht.begin();
  delay(2000);        // the DHT22 is not readable for the first couple of seconds
}

Reading read() {
  static bool priorFailed = false;

  Reading r = {};
  if (priorFailed) r.quality |= Q_PRIOR_FAILED;

  // One attempt. A dropped frame is routine on this part, and the loop's own
  // cadence is the retry — blocking here to try again would stall the link and
  // stretch the sampling interval to recover a value the next cycle produces
  // anyway. A failure is recorded as a null reading, not smoothed over, so a
  // rising failure rate stays visible in the data.
  const float h = dht.readHumidity();
  const float t = dht.readTemperature();

  if (!isnan(h) && !isnan(t)) {
    r.ok = true;
    r.temperature_c = t;
    r.humidity_pct = h;
    r.heat_index_c = dht.computeHeatIndex(t, h, false);
  }

  priorFailed = !r.ok;
  if (!r.ok) return r;

  // Out-of-range values are flagged and kept, never discarded. Whether a reading
  // outside the datasheet means a broken sensor or a genuinely extreme room is a
  // question for the analysis, and it cannot answer it if transport threw the
  // evidence away.
  if (r.temperature_c < TEMP_MIN_C || r.temperature_c > TEMP_MAX_C ||
      r.humidity_pct  < HUM_MIN_PCT || r.humidity_pct  > HUM_MAX_PCT) {
    r.quality |= Q_OUT_OF_RANGE;
  }
  return r;
}

}  // namespace sensor
