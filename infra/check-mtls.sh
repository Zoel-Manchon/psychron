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
# Once the phone has enrolled a hardware key the host holds no key for phone-01,
# so the checks speak as the phone with a one-day certificate minted for this run,
# inside the certificate directory the broker container can see, and deleted on exit.
SCRATCH=".check-$$"
trap 'rm -rf "certs/$SCRATCH"' EXIT
PHONE_CRT="$C/phone-01.crt" PHONE_KEY="$C/phone-01.key"
if [ ! -f certs/phone-01.key ]; then
  ./ephemeral-cert.sh phone-01 "certs/$SCRATCH"
  PHONE_CRT="$C/$SCRATCH/phone-01.crt" PHONE_KEY="$C/$SCRATCH/phone-01.key"
fi
# Four ways the two devices could be confused with each other. Each one is a
# different mistake in the ACL, so each gets its own assertion.
PHONE="psychron/v2/phone-01/probe"
assert allow "phone certificate on its own v2 topic"        -t "$PHONE" --cert "$PHONE_CRT" --key "$PHONE_KEY"
assert deny  "phone certificate writing under v1"        -t "psychron/v1/phone-01/probe" --cert "$PHONE_CRT" --key "$PHONE_KEY"
assert deny  "phone certificate on the ESP32's topic"        -t "$MINE"  --cert "$PHONE_CRT" --key "$PHONE_KEY"
assert deny  "ESP32 certificate on the phone's topic"        -t "$PHONE" --cert "$C/esp32-01.crt" --key "$C/esp32-01.key"

# The key a phone held as a file before it enrolled: revoked, and refused although
# its certificate has not expired. Checked only once there is one to check, and only
# while the certificate is otherwise good — past its expiry, a refusal would prove
# nothing about the revocation list. Copied into a directory of its own: the
# archive is outside what the broker can see, and the file names are the same as
# the credential the other checks are using.
REVOKED=$(ls -t certs.old-*/phone-01.crt 2>/dev/null | head -1 || true)
if [ -n "$REVOKED" ] && [ -f "${REVOKED%.crt}.key" ] &&
   openssl verify -CAfile certs/ca.crt "$REVOKED" >/dev/null 2>&1; then
  mkdir -p "certs/$SCRATCH/revoked"
  cp "$REVOKED" "${REVOKED%.crt}.key" "certs/$SCRATCH/revoked/"
  assert deny  "revoked phone file key"                     -t "$PHONE" \
         --cert "$C/$SCRATCH/revoked/phone-01.crt" --key "$C/$SCRATCH/revoked/phone-01.key"
fi

echo
echo "alerts flow one way, from ingestion to the phone:"
ALERT="psychron/alerts/probe/check"
assert allow "ingestion publishing an alert"                -t "$ALERT" --cert "$C/ingest.crt" --key "$C/ingest.key"
assert deny  "phone publishing an alert"                    -t "$ALERT" --cert "$PHONE_CRT" --key "$PHONE_KEY"
assert deny  "ESP32 publishing an alert"                    -t "$ALERT" --cert "$C/esp32-01.crt" --key "$C/esp32-01.key"

# Reading is the other half. A retained probe, so a subscriber that is allowed to
# read receives it at once and one that is not receives nothing before -W gives up.
# Cleared afterwards: a real phone subscribes to the same tree.
docker compose exec -T broker mosquitto_pub -h localhost -p 8883 -V 5 -q 1 -r -t "$ALERT" \
  -m '{"probe":2}' --cafile "$C/ca.crt" --cert "$C/ingest.crt" --key "$C/ingest.key" >/dev/null 2>&1
receives() {         # receives <cert> <key>
  docker compose exec -T broker mosquitto_sub -h localhost -p 8883 -V 5 -t "$ALERT" -C 1 -W 3 \
    --cafile "$C/ca.crt" --cert "$1" --key "$2" 2>/dev/null | grep -q '"probe":2'
}
for who in "allow $PHONE_CRT $PHONE_KEY phone subscribing to alerts" \
           "deny $C/esp32-01.crt $C/esp32-01.key ESP32 subscribing to alerts"; do
  set -- $who
  expect=$1 crt=$2 key=$3; shift 3
  if receives "$crt" "$key"; then got=allow; else got=deny; fi
  if [ "$got" = "$expect" ]; then
    printf '  OK    %-46s %s\n' "$*" "$got"
  else
    printf '  FAIL  %-46s expected %s, got %s\n' "$*" "$expect" "$got"
    fails=$((fails + 1))
  fi
done
docker compose exec -T broker mosquitto_pub -h localhost -p 8883 -V 5 -q 1 -r -n -t "$ALERT" \
  --cafile "$C/ca.crt" --cert "$C/ingest.crt" --key "$C/ingest.key" >/dev/null 2>&1

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
