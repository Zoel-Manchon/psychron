#!/usr/bin/env bash
# Asserts that the 8883 listener really enforces mutual TLS, and that authority
# now comes from the certificate rather than from a shared secret.
#
# As in check-broker.sh, mosquitto_pub exits 0 even when the broker denies the
# PUBLISH itself — an ACL rejection only appears on stderr. Both signals are
# needed, or the assertions pass no matter what the broker does.
set -uo pipefail
cd "$(dirname "$0")"

C=/mosquitto/certs
fails=0

pub() {
  local out rc
  out=$(docker compose exec -T broker mosquitto_pub -h localhost -p 8883 -V 5 -q 1 \
        -m '{"probe":1}' --cafile "$C/ca.crt" "$@" 2>&1)
  rc=$?
  [ "$rc" -eq 0 ] && ! printf '%s' "$out" | grep -qiE 'not authorized|failed|refused|error|unable'
}

assert() {          # assert <allow|deny> <label> <args...>
  local expect=$1 label=$2; shift 2
  if pub "$@"; then got=allow; else got=deny; fi
  if [ "$got" = "$expect" ]; then
    printf '  OK    %-46s %s\n' "$label" "$got"
  else
    printf '  FAIL  %-46s expected %s, got %s\n' "$label" "$expect" "$got"
    fails=$((fails + 1))
  fi
}

# A certificate from an authority the broker does not know. Without this, the
# suite would only prove that a valid certificate works, not that an invalid one
# is actually turned away.
if [ ! -f certs/rogue.crt ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out certs/rogue-ca.key 2>/dev/null
  openssl req -x509 -new -key certs/rogue-ca.key -sha256 -days 30 \
    -out certs/rogue-ca.crt -subj "/O=rogue/CN=rogue-ca" 2>/dev/null
  openssl ecparam -name prime256v1 -genkey -noout -out certs/rogue.key 2>/dev/null
  openssl req -new -key certs/rogue.key -out certs/rogue.csr -subj "/O=rogue/CN=esp32-01" 2>/dev/null
  openssl x509 -req -in certs/rogue.csr -CA certs/rogue-ca.crt -CAkey certs/rogue-ca.key \
    -CAcreateserial -out certs/rogue.crt -days 30 -sha256 2>/dev/null
  rm -f certs/rogue.csr
fi

# Probes go to their own subtopic. It is still inside the device's ACL scope,
# so the permission boundary is tested exactly as before, but ingestion
# ignores it and the test run no longer shows up as rejected telemetry.
MINE="psychron/v1/esp32-01/probe"
THEIRS="psychron/v1/esp32-99/probe"

echo "mutual TLS on 8883:"
assert allow "device certificate on its own topic" \
       -t "$MINE"   --cert "$C/esp32-01.crt" --key "$C/esp32-01.key"
assert deny  "device certificate on another device's topic" \
       -t "$THEIRS" --cert "$C/esp32-01.crt" --key "$C/esp32-01.key"
# Verified by flipping require_certificate to false and restarting: this stays
# denied either way, so what turns it away is allow_anonymous rather than the
# certificate requirement. Still worth asserting — it is the property that
# matters — but named for what actually enforces it.
assert deny  "neither certificate nor password (anonymous)" \
       -t "$MINE"
assert deny  "certificate signed by an unknown authority" \
       -t "$MINE"   --cert "$C/rogue.crt" --key "$C/rogue.key"
# Any password at all, valid or not: there is no password file left for the
# broker to check one against, so credentials of that shape can never get in.
assert deny  "username and password, with no certificate" \
       -t "$MINE"   -u esp32-01 -P whatever

echo
echo "identity comes from the certificate, not the topic:"
# The rogue certificate carries CN=esp32-01, so if the broker were trusting the
# name rather than the signature this would be indistinguishable from the real
# device. It is refused on the chain, before the name is ever considered.
assert deny  "forged CN=esp32-01 from a foreign CA" \
       -t "$MINE"   --cert "$C/rogue.crt" --key "$C/rogue.key"

echo
echo "the phone node is fenced to its own contract and subtree:"
# Four ways the two devices could be confused with each other. Each one is a
# different mistake in the ACL, so each gets its own assertion.
PHONE="psychron/v2/phone-01/probe"
assert allow "phone certificate on its own v2 topic"        -t "$PHONE" --cert "$C/phone-01.crt" --key "$C/phone-01.key"
assert deny  "phone certificate writing under v1"        -t "psychron/v1/phone-01/probe" --cert "$C/phone-01.crt" --key "$C/phone-01.key"
assert deny  "phone certificate on the ESP32's topic"        -t "$MINE"  --cert "$C/phone-01.crt" --key "$C/phone-01.key"
assert deny  "ESP32 certificate on the phone's topic"        -t "$PHONE" --cert "$C/esp32-01.crt" --key "$C/esp32-01.key"

echo
echo "the plaintext listener is gone:"
# A regression test, not a formality. Plaintext coming back is the failure that
# would go unnoticed for months, because everything keeps working.
if docker compose exec -T broker mosquitto_pub -h localhost -p 1883 -q 1 -m '{"probe":1}' \
     -t "$MINE" -u esp32-01 -P anything >/dev/null 2>&1; then
  echo "  FAIL  1883 is accepting connections again      allow"
  fails=$((fails + 1))
else
  echo "  OK    1883 refuses connections                 deny"
fi

echo
if [ "$fails" -eq 0 ]; then echo "all mTLS checks passed"; else echo "$fails check(s) FAILED"; fi
exit "$fails"
