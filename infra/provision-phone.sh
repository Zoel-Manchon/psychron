#!/usr/bin/env bash
# Enrol the phone node: its key is generated in the phone's secure hardware, and the
# host only ever sees — and signs — a request for it.
#
#   1. The app is asked to generate a P-256 key in StrongBox (the TEE if the phone
#      has none) and to write a PKCS#10 request for it.
#   2. The request is pulled over adb and checked here: its signature, its subject,
#      its curve. A request that fails any of them is refused, not signed.
#   3. The CA signs it into certs/<device>.device.crt, and three public files are
#      pushed back: the CA, the certificate and where the broker is.
#
# Nothing secret crosses the cable in either direction. The app deletes the file key
# an older provisioning pushed as soon as it connects with the hardware one; revoke
# that file key's certificate afterwards, as the last line printed says.
#
# Requires a debug build installed (run-as only works on debuggable apps) and USB or
# wireless debugging enabled. Under Git Bash, run with MSYS_NO_PATHCONV=1.
set -euo pipefail
cd "$(dirname "$0")"

PKG=dev.psychron.node
DEVICE=${DEVICE:-phone-01}
ADB=${ADB:-adb}
TMP_REMOTE=/data/local/tmp/psychron-provision
TMP_LOCAL=$(mktemp -d)
# Under Git Bash, openssl and adb are native Windows programs that do not
# understand MSYS paths such as /tmp/..., and automatic path conversion cannot
# be left on because it would also rewrite the phone-side /data/local/tmp
# paths into Windows ones. So local paths are made native here, and only here.
if command -v cygpath >/dev/null 2>&1; then TMP_LOCAL=$(cygpath -m "$TMP_LOCAL"); fi

cleanup() {
  rm -rf "$TMP_LOCAL"
  "$ADB" shell rm -rf "$TMP_REMOTE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

die() { echo "$*" >&2; exit 1; }

[ -f certs/ca.key ] && [ -f certs/ext.cnf ] || die "no CA: run ./make-certs.sh first"
"$ADB" get-state >/dev/null 2>&1 || die "no phone on adb: connect it and allow USB debugging"

env_value() { sed -n "s/^$1=\([^ #]*\).*/\1/p" .env | head -1; }
HOST=$(env_value PSYCHRON_MQTT_HOST)
[ -n "$HOST" ] && [ "$HOST" != "127.0.0.1" ] ||
  die "PSYCHRON_MQTT_HOST in .env must be this machine's LAN address; the phone cannot reach 127.0.0.1"
# Optional: an address that reaches this host from mobile data. The phone tries
# it first whenever it is not on Wi-Fi. It must already be in the broker's
# certificate, or every connection to it fails hostname verification — which is
# checked here, before anything is pushed, rather than discovered on the phone.
REMOTE=$(env_value PSYCHRON_MQTT_REMOTE_HOST)
HOSTS="$HOST"
if [ -n "$REMOTE" ]; then
  openssl x509 -in certs/broker.crt -noout -ext subjectAltName | grep -qE "(IP Address|DNS):${REMOTE//./\\.}(,|$)" ||
    die "certs/broker.crt does not name $REMOTE: run ./make-certs.sh, then docker compose restart broker"
  HOSTS="$HOST,$REMOTE"
fi

# ── 1. a key in the phone's hardware, and a request for it ───────────────────
"$ADB" shell run-as "$PKG" rm -f files/certs/client.csr >/dev/null 2>&1 || true
"$ADB" shell am start -n "$PKG/.MainActivity" --ez enrol true --es device "$DEVICE" >/dev/null
for _ in $(seq 1 30); do
  "$ADB" shell run-as "$PKG" test -f files/certs/client.csr && break
  sleep 1
done
"$ADB" exec-out run-as "$PKG" cat files/certs/client.csr > "$TMP_LOCAL/client.csr" ||
  die "the app wrote no request: is the debug build installed and the screen unlocked?"

# ── 2. checked before anything is signed ─────────────────────────────────────
openssl req -in "$TMP_LOCAL/client.csr" -noout -verify 2>&1 | grep -q "verify OK" ||
  die "the request's signature does not verify"
subject=$(openssl req -in "$TMP_LOCAL/client.csr" -noout -subject -nameopt RFC2253)
[ "$subject" = "subject=CN=$DEVICE,O=psychron" ] || die "unexpected subject: $subject"
openssl req -in "$TMP_LOCAL/client.csr" -noout -text | grep -q "NIST CURVE: P-256" ||
  die "the request is not for a P-256 key"

# ── 3. signed, and the public half pushed back ───────────────────────────────
openssl x509 -req -in "$TMP_LOCAL/client.csr" -CA certs/ca.crt -CAkey certs/ca.key -CAcreateserial \
  -out "certs/$DEVICE.device.crt" -days 730 -sha256 -extfile certs/ext.cnf -extensions client 2>/dev/null

cp certs/ca.crt "$TMP_LOCAL/ca.crt"
cp "certs/$DEVICE.device.crt" "$TMP_LOCAL/client.crt"
printf 'hosts=%s\nport=8883\ndevice=%s\n' "$HOSTS" "$DEVICE" > "$TMP_LOCAL/node.properties"

"$ADB" shell mkdir -p "$TMP_REMOTE"
for f in ca.crt client.crt node.properties; do
  "$ADB" push "$TMP_LOCAL/$f" "$TMP_REMOTE/$f" >/dev/null
done
"$ADB" shell run-as "$PKG" sh -c "'mkdir -p files/certs && cp $TMP_REMOTE/* files/certs/ && chmod 600 files/certs/*'"

echo "  enrolled $DEVICE -> ${HOSTS//,/ | } :8883"
openssl x509 -in "certs/$DEVICE.device.crt" -noout -subject -serial -enddate | sed 's/^/    /'
"$ADB" shell run-as "$PKG" ls -l files/certs
if [ -f "certs/$DEVICE.crt" ]; then
  echo
  echo "  Once the app shows 'identity · hardware key', revoke the file key it had before:"
  echo "    ./make-certs.sh revoke $DEVICE && docker compose restart broker"
fi
