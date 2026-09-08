#include "netlink.h"
#include "config.h"

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <esp_system.h>
#include <time.h>

#include "certstore.h"

namespace {

// Any epoch past this cannot have come from an unsynced clock, which starts
// at the beginning of 1970. A fixed floor is more honest than trusting a
// "synced" flag the SNTP client sets before the first packet ever lands.
const time_t EPOCH_FLOOR = 1735689600;   // 2025-01-01

WiFiClientSecure tls;
PubSubClient     mqtt(tls);
bool             tlsConfigured = false;

char topicReading[96];
char topicStatus[96];
char topicBoot[96];

uint32_t lastWifiTry = 0;
uint32_t lastMqttTry = 0;
uint32_t lastNtpSync = 0;
bool     ntpStarted  = false;


void buildTopics() {
  snprintf(topicReading, sizeof(topicReading), "%s%s%s", TOPIC_PREFIX, DEVICE_ID, TOPIC_READING);
  snprintf(topicStatus,  sizeof(topicStatus),  "%s%s%s", TOPIC_PREFIX, DEVICE_ID, TOPIC_STATUS);
  snprintf(topicBoot,    sizeof(topicBoot),    "%s%s%s", TOPIC_PREFIX, DEVICE_ID, TOPIC_BOOT);
}

void startNtp() {
  configTime(0, 0, "pool.ntp.org", "time.cloudflare.com");
  ntpStarted = true;
  lastNtpSync = millis();
}

void serviceWifi() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (lastWifiTry && millis() - lastWifiTry < WIFI_RETRY_MS) return;

  lastWifiTry = millis();
  WiFi.disconnect();
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

void serviceMqtt() {
  if (WiFi.status() != WL_CONNECTED) return;
  if (mqtt.connected()) return;

  // No certificates yet: the node stays off the broker and keeps buffering. It
  // never falls back to the plaintext port — a silent downgrade to cleartext is
  // worse than a visible outage, because nobody ever notices it.
  if (!certstore::complete()) return;

  // The clock gate, and the reason it exists: verifying a certificate means
  // checking notBefore and notAfter against the current time. A device that has
  // just booted believes it is 1970, so every certificate is "not yet valid" and
  // the handshake fails with an error that blames the certificate rather than
  // the clock. NTP first, always.
  if (time(nullptr) <= EPOCH_FLOOR) return;

  if (lastMqttTry && millis() - lastMqttTry < MQTT_RETRY_MS) return;

  lastMqttTry = millis();

  if (!tlsConfigured) {
    tls.setCACert(certstore::ca());
    tls.setCertificate(certstore::cert());
    tls.setPrivateKey(certstore::key());
    tlsConfigured = true;
  }

  // The will is retained so a consumer that subscribes after the node dies still
  // learns it is down, instead of seeing an empty topic and assuming silence.
  // No username or password: on the mutual-TLS listener the broker takes the
  // identity from the certificate CN, so there is no shared secret to hold.
  const bool ok = mqtt.connect(DEVICE_ID, nullptr, nullptr,
                               topicStatus, 1, true, "offline");
  if (ok) {
    mqtt.publish(topicStatus, "online", true);
    return;
  }

  // A failed TLS handshake looks identical to a refused MQTT connection from
  // the outside, and the broker only ever sees "the client hung up". The
  // device is the only place that knows which of the two it was, and what
  // mbedTLS objected to, so it has to say so.
  char err[128] = {0};
  tls.lastError(err, sizeof(err));
  Serial.printf("MQTT;connect_failed;state=%d;tls=%s;heap=%u\n",
                mqtt.state(), err[0] ? err : "none",
                (unsigned)ESP.getFreeHeap());
}

}  // namespace

namespace netlink {

void begin() {
  buildTopics();

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.setSleep(false);          // modem sleep adds seconds of latency to a publish
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  lastWifiTry = millis();

  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setKeepAlive(MQTT_KEEPALIVE_S);
  // PubSubClient drops any message larger than its buffer without reporting it,
  // so this has to be raised past the payload size rather than left at 256.
  mqtt.setBufferSize(MQTT_BUFFER_BYTES);
}

void loop() {
  serviceWifi();

  if (WiFi.status() == WL_CONNECTED) {
    if (!ntpStarted) startNtp();
    else if (millis() - lastNtpSync > NTP_RESYNC_MS) startNtp();
  }

  serviceMqtt();
  if (mqtt.connected()) mqtt.loop();
}

LinkState state() {
  if (WiFi.status() != WL_CONNECTED) return LinkState::Down;
  return mqtt.connected() ? LinkState::Up : LinkState::WifiOnly;
}

const char *stateLabel() {
  switch (state()) {
    case LinkState::Down:     return "NO WIFI";
    // Distinguishes "cannot reach the broker" from "not allowed to try yet",
    // which are different problems with the same symptom on the panel.
    case LinkState::WifiOnly: return certstore::complete() ? "NO MQTT" : "NO CERTS";
    default:                  return "ONLINE";
  }
}

bool clockSynced() {
  return time(nullptr) > EPOCH_FLOOR;
}

int rssi() {
  return WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0;
}

const char *resetReason() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON:  return "poweron";
    case ESP_RST_EXT:      return "external";
    case ESP_RST_SW:       return "software";
    case ESP_RST_PANIC:    return "panic";
    case ESP_RST_INT_WDT:  return "int_wdt";
    case ESP_RST_TASK_WDT: return "task_wdt";
    case ESP_RST_WDT:      return "wdt";
    case ESP_RST_BROWNOUT: return "brownout";
    case ESP_RST_DEEPSLEEP: return "deepsleep";
    default:               return "unknown";
  }
}

bool publish(const char *topicSuffix, const char *payload, bool retain) {
  if (!mqtt.connected()) return false;

  const char *topic = topicReading;
  if (strcmp(topicSuffix, TOPIC_BOOT) == 0)        topic = topicBoot;
  else if (strcmp(topicSuffix, TOPIC_STATUS) == 0) topic = topicStatus;

  return mqtt.publish(topic, payload, retain);
}

}  // namespace netlink
