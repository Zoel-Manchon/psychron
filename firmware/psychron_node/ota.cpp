#include <Arduino.h>
#include "ota.h"
#include "config.h"
#include "certstore.h"

#include <HTTPClient.h>
#include <Update.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <mbedtls/sha256.h>

namespace {

uint32_t lastCheck = 0;
char     result[40] = "never checked";

void setResult(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(result, sizeof(result), fmt, ap);
  va_end(ap);
}

// The manifest has four fields of known shape and the image is already at 83%
// of flash, so a full JSON parser would cost more than it is worth here. This
// is deliberately literal: it finds "key":"value" and nothing cleverer, and any
// manifest it cannot read is treated as no update rather than as a guess.
bool field(const String &json, const char *key, char *out, size_t n) {
  String needle = String("\"") + key + "\"";
  int k = json.indexOf(needle);
  if (k < 0) return false;
  int colon = json.indexOf(':', k + needle.length());
  if (colon < 0) return false;

  int start = colon + 1;
  while (start < (int)json.length() && (json[start] == ' ' || json[start] == '"')) start++;
  int end = start;
  while (end < (int)json.length() && json[end] != '"' && json[end] != ',' &&
         json[end] != '}' && json[end] != ' ') end++;
  if (end <= start || (size_t)(end - start) >= n) return false;

  json.substring(start, end).toCharArray(out, n);
  return true;
}

void hexDigest(const uint8_t *raw, char *out) {
  static const char *H = "0123456789abcdef";
  for (int i = 0; i < 32; i++) {
    out[i * 2]     = H[raw[i] >> 4];
    out[i * 2 + 1] = H[raw[i] & 0x0F];
  }
  out[64] = '\0';
}

void configure(WiFiClientSecure &client) {
  client.setCACert(certstore::ca());
  client.setCertificate(certstore::cert());
  client.setPrivateKey(certstore::key());
}

// Streams the image into the OTA partition while hashing it, and refuses to
// commit if the digest does not match. Update.end() is the point of no return,
// so nothing reaches it until the whole image has been seen and verified.
bool download(const char *path, const char *expectDigest, long expectSize) {
  WiFiClientSecure client;
  configure(client);

  HTTPClient http;
  String url = String("https://") + MQTT_HOST + ":" + OTA_PORT + path;
  if (!http.begin(client, url)) {
    setResult("image: begin failed");
    return false;
  }

  const int code = http.GET();
  if (code != HTTP_CODE_OK) {
    setResult("image: HTTP %d", code);
    http.end();
    return false;
  }

  const int size = http.getSize();
  if (size <= 0 || (expectSize > 0 && size != expectSize)) {
    setResult("image: size %d vs %ld", size, expectSize);
    http.end();
    return false;
  }

  if (!Update.begin(size)) {
    setResult("image: no room, %s", Update.errorString());
    http.end();
    return false;
  }

  mbedtls_sha256_context sha;
  mbedtls_sha256_init(&sha);
  mbedtls_sha256_starts(&sha, 0);

  WiFiClient *stream = http.getStreamPtr();
  uint8_t buf[1024];
  int remaining = size;

  while (remaining > 0 && http.connected()) {
    const size_t avail = stream->available();
    if (avail == 0) { delay(1); continue; }

    const int got = stream->readBytes(buf, min(avail, sizeof(buf)));
    if (got <= 0) break;

    mbedtls_sha256_update(&sha, buf, got);
    if (Update.write(buf, got) != (size_t)got) {
      setResult("image: write failed");
      mbedtls_sha256_free(&sha);
      Update.abort();
      http.end();
      return false;
    }
    remaining -= got;
  }

  uint8_t raw[32];
  mbedtls_sha256_finish(&sha, raw);
  mbedtls_sha256_free(&sha);
  http.end();

  if (remaining != 0) {
    setResult("image: short by %d", remaining);
    Update.abort();
    return false;
  }

  char got[65];
  hexDigest(raw, got);
  if (strcasecmp(got, expectDigest) != 0) {
    // The bytes are already in the inactive partition, but nothing has told the
    // bootloader to use it. Aborting here leaves the running firmware untouched.
    setResult("image: digest mismatch");
    Update.abort();
    return false;
  }

  if (!Update.end(true)) {
    setResult("image: commit %s", Update.errorString());
    return false;
  }
  return true;
}

}  // namespace

namespace ota {

void begin() { lastCheck = 0; }

const char *lastResult() { return result; }

void poll() {
  if (WiFi.status() != WL_CONNECTED) return;
  if (!certstore::complete()) return;
  if (lastCheck && millis() - lastCheck < OTA_CHECK_MS) return;
  lastCheck = millis();

  WiFiClientSecure client;
  configure(client);

  HTTPClient http;
  String url = String("https://") + MQTT_HOST + ":" + OTA_PORT + OTA_MANIFEST;
  if (!http.begin(client, url)) {
    setResult("manifest: begin failed");
    return;
  }

  const int code = http.GET();
  if (code != HTTP_CODE_OK) {
    setResult("manifest: HTTP %d", code);
    http.end();
    return;
  }

  const String body = http.getString();
  http.end();

  char version[24], image[64], digest[72], sizeText[16];
  if (!field(body, "version", version, sizeof(version)) ||
      !field(body, "image", image, sizeof(image)) ||
      !field(body, "sha256", digest, sizeof(digest))) {
    setResult("manifest: unreadable");
    return;
  }
  const long expectSize = field(body, "size", sizeText, sizeof(sizeText)) ? atol(sizeText) : 0;

  // String equality, not ordering: comparing versions numerically invites a
  // node to refuse the rollback that is the whole point of keeping two slots.
  if (strcmp(version, FW_VERSION) == 0) {
    setResult("up to date %s", FW_VERSION);
    return;
  }

  Serial.printf("OTA;current=%s;offered=%s;size=%ld\n", FW_VERSION, version, expectSize);
  if (!download(image, digest, expectSize)) {
    Serial.printf("OTA;failed;%s\n", result);
    return;
  }

  Serial.printf("OTA;installed=%s;restarting\n", version);
  delay(200);                 // let the line reach the host before the reset
  ESP.restart();
}

}  // namespace ota
