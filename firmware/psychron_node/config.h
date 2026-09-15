#pragma once
// Everything tunable in one place. Credentials live in secrets.h, which is not
// in git — copy secrets.h.example over it and fill it in.

// Pulled in here so every translation unit that needs the node's identity gets
// it from one place. The guard turns a missing file into an instruction rather
// than a scattering of "DEVICE_ID was not declared" further down the build.
#if __has_include("secrets.h")
  #include "secrets.h"
#else
  #error "secrets.h is missing: copy secrets.h.example to secrets.h and fill it in"
#endif

#define FW_VERSION        "1.2.3"
#define CONTRACT_VERSION  1

// ── Wiring ───────────────────────────────────────────────────────────────────
#define I2C_SDA           25
#define I2C_SCL           26
#define I2C_HZ            400000L
#define DHT_PIN           27
#define DHT_TYPE          DHT22

#define SCREEN_W          128
#define SCREEN_H          64
#define OLED_RESET        -1

// ── Timing ───────────────────────────────────────────────────────────────────
// Must stay clear of the DHT library's own 2000 ms cache, not merely equal to it.
// The library stamps its _lastreadtime after the read completes, so a 2000 ms loop
// comes back ~5 ms early and is served the previous measurement instead — every
// reading a silent duplicate of the one before, indistinguishable from a room that
// simply is not changing.
#define READ_PERIOD_MS    3000UL
#define TREND_PERIOD_MS   30000UL    // one trend bar per 30 s
#define TREND_BARS        21         // 21 bars on the 6 px grid, a ~10 min window

#define WIFI_RETRY_MS     5000UL
#define MQTT_RETRY_MS     5000UL
#define NTP_RESYNC_MS     3600000UL  // an hour; the RTC drifts, but slowly

// A reading whose interval strays this far from nominal gets quality bit 4. The
// loop is cooperative, so a slow I2C write or a reconnect can stretch a cycle.
#define INTERVAL_TOLERANCE_MS  500UL

// ── MQTT ─────────────────────────────────────────────────────────────────────
// Topics carry the device id for the broker ACL to fence on, but identity is
// never taken from the topic downstream — see docs/CONTRACT.md.
#define TOPIC_PREFIX      "psychron/v1/"
#define TOPIC_READING     "/reading"
#define TOPIC_STATUS      "/status"    // retained: "online" / "offline" via LWT
#define TOPIC_BOOT        "/boot"

// 15 s rather than 30: a dead stream is only noticed after up to two keepalives,
// and every reading published in that time has to be held for replay. The cost is
// one 2-byte ping every 15 s.
#define MQTT_KEEPALIVE_S  15

// Every network call runs inside the sampling loop, so its worst case is a gap in
// the record. The core's defaults are a 30 s TCP connect and a 120 s TLS handshake:
// measured, one reconnect attempt during a host network flap stopped sampling for
// 37 s. On a LAN a connect answers in milliseconds and a P-256 handshake takes
// about two seconds, so these bound an attempt that is going to fail anyway.
#define NET_TIMEOUT_MS     5000UL      // TCP connect, and each socket read or write
#define TLS_HANDSHAKE_S    8UL
#define MQTT_SOCKET_S      5           // waiting for CONNACK
#define MQTT_BUFFER_BYTES 512          // a reading is ~150 B; PubSubClient
                                       // silently drops anything over its buffer

// How long a publish stays unproven: two keepalives to notice a dead stream, plus
// 15 s for TCP to give up retransmitting what was already in flight. See inflight.h.
#define UNCONFIRMED_HORIZON_MS  (2UL * MQTT_KEEPALIVE_S * 1000UL + 15000UL)
// Kept free in the unconfirmed window for live readings while a backlog drains,
// so replay can never push a fresh reading out of it. 45 s of readings, doubled.
#define LIVE_HEADROOM           32

// ── Firmware updates ─────────────────────────────────────────────────────────
// Served from the same host as the broker, behind the same CA and the same
// client certificate: a node that may publish is a node that may update.
#define OTA_PORT       8443
#define OTA_MANIFEST   "/manifest.json"
#define OTA_CHECK_MS   900000UL    // every 15 min; a check is a blocking round trip

// ── Offline store ────────────────────────────────────────────────────────────
// Only written while the link is down, so the flash sees no wear in normal
// operation. 5000 records is a little under three hours at the read period.
#define STORE_CAPACITY    5000
// The single-file ring of versions before 1.2.3: read once at boot, its backlog
// carried into the split layout, and removed. See store.cpp.
#define STORE_PATH        "/queue.bin"

// ── Sensor limits ────────────────────────────────────────────────────────────
#define TEMP_MIN_C        -40.0f
#define TEMP_MAX_C        80.0f
#define HUM_MIN_PCT       0.0f
#define HUM_MAX_PCT       100.0f
// No in-read retry: the loop's own cadence is the retry. Blocking a further
// 3 s inside a read to try again would stall the MQTT keepalive and stretch the
// sampling interval, to recover a reading the next cycle produces anyway.

// ── Quality bitfield, mirrors docs/CONTRACT.md ───────────────────────────────
#define Q_CLOCK_UNSYNCED  0x01
#define Q_REPLAYED        0x02
#define Q_PRIOR_FAILED    0x04       // the preceding read returned nothing
#define Q_OUT_OF_RANGE    0x08
#define Q_INTERVAL_DRIFT  0x10
