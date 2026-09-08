#include <Arduino.h>
#include "certstore.h"

#include <Preferences.h>

namespace {

Preferences prefs;
bool opened = false;

// Held for the life of the process: mbedTLS keeps pointers to these buffers, so
// they cannot be stack temporaries handed to setCACert and then dropped.
char *ca_pem = nullptr;
char *crt_pem = nullptr;
char *key_pem = nullptr;

char **slot_ptr(const char *slot) {
  if (strcmp(slot, "ca") == 0) return &ca_pem;
  if (strcmp(slot, "crt") == 0) return &crt_pem;
  if (strcmp(slot, "key") == 0) return &key_pem;
  return nullptr;
}

char *load(const char *slot) {
  const size_t len = prefs.getBytesLength(slot);
  if (len == 0 || len > 8192) return nullptr;

  char *buf = (char *)malloc(len + 1);
  if (buf == nullptr) return nullptr;
  if (prefs.getBytes(slot, buf, len) != len) {
    free(buf);
    return nullptr;
  }
  buf[len] = '\0';
  return buf;
}

}  // namespace

namespace certstore {

bool begin() {
  if (!prefs.begin("psychron", false)) return false;
  opened = true;
  ca_pem = load("ca");
  crt_pem = load("crt");
  key_pem = load("key");
  return true;
}

bool complete() {
  return ca_pem != nullptr && crt_pem != nullptr && key_pem != nullptr;
}

const char *ca()   { return ca_pem; }
const char *cert() { return crt_pem; }
const char *key()  { return key_pem; }

bool store(const char *slot, const uint8_t *pem, size_t len) {
  if (!opened || len == 0 || len > 8192) return false;
  char **target = slot_ptr(slot);
  if (target == nullptr) return false;

  if (prefs.putBytes(slot, pem, len) != len) return false;

  free(*target);
  *target = (char *)malloc(len + 1);
  if (*target == nullptr) return false;
  memcpy(*target, pem, len);
  (*target)[len] = '\0';
  return true;
}

void erase() {
  if (!opened) return;
  prefs.clear();
  free(ca_pem);  ca_pem = nullptr;
  free(crt_pem); crt_pem = nullptr;
  free(key_pem); key_pem = nullptr;
}

}  // namespace certstore
