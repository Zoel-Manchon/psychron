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

#include <sys/time.h>
#include "config.h"
#include "certstore.h"
#include "inflight.h"
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

// Global rather than on the stack: the window is ~9 KB and the loop task has 8.
static inflight::Window unconfirmed(UNCONFIRMED_HORIZON_MS);
static uint32_t unconfirmedSession = 0;
static uint32_t lostReadings = 0;

static bool publishRecord(const StoreRecord &r) {
  char payload[MQTT_BUFFER_BYTES];
  // A zero length means the payload did not fit and buf holds a truncated
  // string. Sending it would put a malformed record on the wire that ingestion
  // rejects long after the reading it described is gone, so it is kept instead.
  if (buildReadingPayload(payload, sizeof(payload), IDENTITY, r) == 0) {
    Serial.println(F("payload: reading did not fit the buffer, not sent"));
    return false;
  }
  if (!netlink::publish(TOPIC_READING, payload)) return false;
  // Accepted by the client is not received by the broker; held until it is proven.
  unconfirmed.sent(r, millis());
  return true;
}

// The session every held publish went out on has ended — dropped, or replaced by
// a reconnect inside one pass. What is still unproven goes back to the store and
// is replayed. Records already queued there are newer, so a flap in the middle of
// a drain replays these after them; ingestion places each reading by its own
// clock and seq, so the order of arrival changes nothing that is stored.
static void reclaimUnconfirmed() {
  const bool up = netlink::state() == LinkState::Up;
  if (up && netlink::session() == unconfirmedSession) return;

  if (unconfirmed.size() > 0) {
    const size_t n = unconfirmed.reclaim(millis(), [](const StoreRecord &r) {
      StoreRecord again = r;
      again.quality |= Q_REPLAYED;
      if (!store::push(again)) lostReadings++;
    });
    Serial.printf("LINK;session_ended;requeued=%u\n", (unsigned)n);
  }
  if (up) unconfirmedSession = netlink::session();
}

// Buffered readings go out before anything new, so the series arrives in the
// order it was measured and a consumer never sees a gap close from both ends.
static void drainStore() {
  if (netlink::state() != LinkState::Up) return;

  // A drained record leaves the store the moment it is published, so it is only
  // safe while the unconfirmed window can still hold it. That bounds replay to
  // about 5 records a second, under twenty minutes for a full store.
  unconfirmed.expire(millis());
  for (uint8_t sent = 0; sent < 20 && store::count() > 0 && unconfirmed.room() > LIVE_HEADROOM;
       sent++) {
    StoreRecord r;
    if (!store::peek(r)) break;
    r.quality |= Q_REPLAYED;
    if (!publishRecord(r)) break;      // link went again; keep it for next time
    store::pop();
  }
}

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
  // Straight after the link is serviced, before anything is drained or read: a
  // reading taken this pass must queue behind the ones it outlived.
  reclaimUnconfirmed();

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
    // gettimeofday rather than time(): the clock is kept to the microsecond by
    // SNTP, and throwing the fraction away made every reading up to a second
    // early, which is coarser than the difference being measured between nodes.
    timeval tv;
    gettimeofday(&tv, nullptr);
    rec.ts    = (int32_t)tv.tv_sec;
    rec.ts_ms = (uint16_t)(tv.tv_usec / 1000);
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
