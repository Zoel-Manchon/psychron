#include <Arduino.h>
#include "provisioning.h"
#include "certstore.h"

#include <mbedtls/base64.h>

namespace {

// Transfers are chunked and acknowledged rather than sent as one long line.
// A whole certificate in base64 is around 900 characters, and getting that
// through in one piece depends on the serial receive buffer, on how long the
// loop spends elsewhere, and on the driver — all things that were guessed at
// and got it wrong. With short chunks the host cannot outrun the device,
// because it waits for a reply before sending the next one, and the transfer
// stops depending on any buffer size at all.
constexpr size_t kLineMax = 512;     // comfortably above one chunk
constexpr size_t kStageMax = 4096;   // base64 of the largest credential

char   line[kLineMax];
size_t used = 0;
bool   overflowed = false;

char   stage[kStageMax];
size_t staged = 0;
char   stage_slot[8] = {0};

uint8_t decoded[2048];

void handle_begin(char *args) {
  char *slot = strtok(args, " \r\n");
  if (slot == nullptr || strlen(slot) >= sizeof(stage_slot)) {
    Serial.println(F("ERR usage: BEGIN <ca|crt|key>"));
    return;
  }
  strncpy(stage_slot, slot, sizeof(stage_slot) - 1);
  stage_slot[sizeof(stage_slot) - 1] = '\0';
  staged = 0;
  Serial.printf("OK begin %s\n", stage_slot);
}

void handle_chunk(char *args) {
  char *part = strtok(args, " \r\n");
  if (part == nullptr) {
    Serial.println(F("ERR empty chunk"));
    return;
  }
  const size_t len = strlen(part);
  if (staged + len >= kStageMax) {
    Serial.println(F("ERR staging buffer full"));
    staged = 0;
    return;
  }
  memcpy(stage + staged, part, len);
  staged += len;
  // The acknowledgement is the flow control: the host sends the next chunk only
  // after seeing this, so it can never get ahead of the device.
  Serial.printf("OK chunk %u\n", (unsigned)staged);
}

void handle_end() {
  if (stage_slot[0] == '\0' || staged == 0) {
    Serial.println(F("ERR nothing staged"));
    return;
  }
  stage[staged] = '\0';

  size_t out_len = 0;
  const int rc = mbedtls_base64_decode(decoded, sizeof(decoded), &out_len,
                                       (const unsigned char *)stage, staged);
  if (rc != 0) {
    Serial.printf("ERR base64 decode failed, rc=%d, staged %u chars\n",
                  rc, (unsigned)staged);
    staged = 0;
    return;
  }

  // A PEM that does not start with a header is almost always the wrong slot or
  // a mangled transfer. Catching it here costs nothing; catching it at the TLS
  // handshake costs a reflash and an error that blames the wrong thing.
  if (out_len < 32 || memcmp(decoded, "-----BEGIN", 10) != 0) {
    Serial.println(F("ERR payload is not PEM"));
    staged = 0;
    return;
  }

  const bool ok = certstore::store(stage_slot, decoded, out_len);
  staged = 0;
  if (!ok) {
    Serial.println(F("ERR could not write to NVS"));
    return;
  }
  Serial.printf("OK stored %s %u\n", stage_slot, (unsigned)out_len);
}

void handle_line(char *text) {
  while (*text == ' ') text++;
  if (*text == '\0') return;

  if (strncmp(text, "BEGIN ", 6) == 0)      handle_begin(text + 6);
  else if (strncmp(text, "C ", 2) == 0)     handle_chunk(text + 2);
  else if (strncmp(text, "END", 3) == 0)    handle_end();
  else if (strncmp(text, "STATUS", 6) == 0) provisioning::announce();
  else if (strncmp(text, "ERASE", 5) == 0) { certstore::erase(); Serial.println(F("OK erased")); }
  else Serial.println(F("ERR unknown command"));
}

}  // namespace

namespace provisioning {

void announce() {
  Serial.printf("PROVISION;ca=%s;crt=%s;key=%s;complete=%s\n",
                certstore::ca() ? "yes" : "no",
                certstore::cert() ? "yes" : "no",
                certstore::key() ? "yes" : "no",
                certstore::complete() ? "yes" : "no");
}

void poll() {
  while (Serial.available() > 0) {
    const char c = (char)Serial.read();

    if (c == '\n' || c == '\r') {
      if (overflowed) {
        Serial.println(F("ERR line too long"));
      } else if (used > 0) {
        line[used] = '\0';
        handle_line(line);
      }
      used = 0;
      overflowed = false;
      continue;
    }

    if (used + 1 >= kLineMax) {
      // Keep consuming to the end of the line rather than treating the tail as
      // a fresh command, which would turn one oversized line into a burst of
      // "unknown command" noise.
      overflowed = true;
      continue;
    }
    line[used++] = c;
  }
}

}  // namespace provisioning
