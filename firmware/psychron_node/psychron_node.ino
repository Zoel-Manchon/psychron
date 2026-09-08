/*
 * Psychron node — ESP32 WROOM-32 + DHT22 + SSD1306
 *
 * Reads the sensor, shows it on the panel, and publishes contract v1 over MQTT.
 * When the link is down readings go to a flash ring buffer and are drained in
 * order on recovery, so an outage costs latency rather than data.
 *
 * Libraries (Arduino IDE -> Library Manager):
 *   "Adafruit SSD1306", "Adafruit GFX Library", "DHT sensor library",
 *   "Adafruit Unified Sensor", "PubSubClient"
 * Board: esp32 by Espressif -> "ESP32 Dev Module"
 *
 * Copy secrets.h.example to secrets.h before building.
 */

#include "config.h"
#include "certstore.h"
#include "netlink.h"
#include "ota.h"
#include "payload.h"
#include "panel.h"
#include "provisioning.h"
#include "sensor.h"
#include "store.h"

#include <esp_system.h>
#include <time.h>

static uint32_t bootId = 0;
static uint32_t seq    = 0;

// ── transport ────────────────────────────────────────────────────────────────

static const NodeIdentity IDENTITY = {DEVICE_ID, FW_VERSION, CONTRACT_VERSION};

static bool publishRecord(const StoreRecord &r) {
  char payload[MQTT_BUFFER_BYTES];
  // A zero length means the payload did not fit and buf holds a truncated
  // string. Sending it would put a malformed record on the wire that ingestion
  // rejects long after the reading it described is gone, so it is kept instead.
  if (buildReadingPayload(payload, sizeof(payload), IDENTITY, r) == 0) {
    Serial.println(F("payload: reading did not fit the buffer, not sent"));
    return false;
  }
  return netlink::publish(TOPIC_READING, payload);
}

// Buffered readings go out before anything new, so the series arrives in the
// order it was measured and a consumer never sees a gap close from both ends.
static void drainStore() {
  if (netlink::state() != LinkState::Up) return;

  for (uint8_t sent = 0; sent < 20 && store::count() > 0; sent++) {
    StoreRecord r;
    if (!store::peek(r)) break;
    r.quality |= Q_REPLAYED;
    if (!publishRecord(r)) break;      // link went again; keep it for next time
    store::pop();
  }
}

static uint32_t lostReadings = 0;

static void handOff(StoreRecord &r) {
  // Live only when the queue is already empty. Publishing a fresh reading past a
  // backlog would deliver it out of order, which is worse than a few seconds of
  // extra latency on a series that is about to be replayed anyway.
  const bool canGoLive = netlink::state() == LinkState::Up && store::count() == 0;
  if (canGoLive && publishRecord(r)) return;

  // Neither sent nor stored. Counting it is the difference between a dataset with
  // a known hole and one that quietly disagrees with reality.
  if (!store::push(r)) {
    lostReadings++;
    Serial.printf("LOST;seq=%u;total=%u\n", (unsigned)r.seq, (unsigned)lostReadings);
  }
}

// ── panel ────────────────────────────────────────────────────────────────────

static void buildFooter(char *buf, size_t n) {
  const uint32_t queued = store::count();
  if (netlink::state() == LinkState::Up && queued == 0) {
    snprintf(buf, n, "ONLINE %lus", (unsigned long)(READ_PERIOD_MS / 1000));
  } else if (netlink::state() == LinkState::Up) {
    snprintf(buf, n, "SYNC %lu", (unsigned long)queued);
  } else {
    snprintf(buf, n, "%s %lu", netlink::stateLabel(), (unsigned long)queued);
  }
}

// ── lifecycle ────────────────────────────────────────────────────────────────

static void announceBoot() {
  char payload[256];
  const size_t n = buildBootPayload(payload, sizeof(payload), IDENTITY, bootId,
                                    netlink::resetReason(), netlink::rssi(),
                                    store::count(), store::dropped(),
                                    store::healthy());
  if (n) netlink::publish(TOPIC_BOOT, payload, true);
}

void setup() {
  // A provisioning line carries a whole certificate in base64, around 900
  // bytes, and the default RX buffer is 256. Anything longer is silently
  // dropped between loop passes, which surfaces much later as a corrupt
  // certificate rather than as a lost byte.
  Serial.setRxBufferSize(4096);
  Serial.begin(115200);
  delay(300);

  // Identity of this run. seq restarts at 1 on every boot, so without this a
  // reboot would look like a gap and a redelivery would look legitimate.
  bootId = esp_random();

  panel::begin();
  panel::banner("psychron node", FW_VERSION);

  if (!certstore::begin()) {
    Serial.println(F("certstore: NVS unavailable, the node cannot hold credentials"));
  }
  provisioning::announce();

  if (!store::begin()) {
    Serial.println(F("store: filesystem unavailable, readings will not survive an outage"));
  }

  sensor::begin();
  netlink::begin();
  ota::begin();

  Serial.printf("BOOT;fw=%s;dev=%s;boot=%u;reset=%s;queued=%u\n",
                FW_VERSION, DEVICE_ID, (unsigned)bootId,
                netlink::resetReason(), (unsigned)store::count());
}

void loop() {
  // Serviced before anything else so a node waiting to be provisioned still
  // answers, and keeps sampling into the flash buffer while it waits.
  provisioning::poll();
  netlink::loop();

  static bool announced = false;
  if (!announced && netlink::state() == LinkState::Up) {
    announceBoot();
    announced = true;
  }

  drainStore();

  static uint32_t lastRead = 0;
  const uint32_t now = millis();
  if (now - lastRead < READ_PERIOD_MS) return;

  const uint32_t elapsed = now - lastRead;
  const bool firstRead = lastRead == 0;
  lastRead = now;

  const Reading r = sensor::read();
  seq++;

  StoreRecord rec = {};
  rec.boot      = bootId;
  rec.seq       = seq;
  rec.uptime_ms = now;
  rec.quality   = r.quality;

  if (netlink::clockSynced()) {
    rec.ts = (int32_t)time(nullptr);
  } else {
    rec.ts = 0;
    rec.quality |= Q_CLOCK_UNSYNCED;
  }

  // A cycle can stretch when a reconnect or a slow I2C write gets in the way.
  // Flagging it means an analysis can tell a real sampling gap from a late read.
  if (!firstRead && elapsed > READ_PERIOD_MS + INTERVAL_TOLERANCE_MS) {
    rec.quality |= Q_INTERVAL_DRIFT;
  }

  rec.temperature_c = r.ok ? r.temperature_c : NAN;
  rec.humidity_pct  = r.ok ? r.humidity_pct  : NAN;

  handOff(rec);

  char footer[24];
  buildFooter(footer, sizeof(footer));

  if (r.ok) {
    static uint32_t lastTrend = 0;
    if (lastTrend == 0 || now - lastTrend >= TREND_PERIOD_MS) {
      lastTrend = now;
      panel::pushTrend(r.temperature_c);
    }
    panel::reading(r, seq, footer);
  } else {
    panel::fault("DHT22 read failed", "check GPIO27 wiring", footer);
  }

  Serial.printf("DATA;seq=%u;ok=%d;q=%u;queued=%u;link=%s\n",
                (unsigned)seq, r.ok ? 1 : 0, (unsigned)rec.quality,
                (unsigned)store::count(), netlink::stateLabel());

  // Last in the pass, so an update check never delays a reading on its way to
  // storage. The download itself blocks for several seconds and costs a few
  // samples, which is why it runs on a long interval rather than every pass.
  ota::poll();
}
