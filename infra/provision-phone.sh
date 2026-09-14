#!/usr/bin/env bash
# Load the phone node's identity onto a connected phone, over USB.
#
# The key never enters the APK and never touches shared storage for longer than
# the copy takes: it goes to /data/local/tmp, is copied by the app's own uid into
# its private directory, and the temporary copy is deleted before this exits —
# also when a step fails, through the trap below.
#
# Requires a debug build installed (run-as only works on debuggable apps) and
# USB debugging enabled on the phone.
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

[ -f "certs/$DEVICE.key" ] || { echo "no certs/$DEVICE.key: run ./make-certs.sh first" >&2; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "no phone on adb: connect it and allow USB debugging" >&2; exit 1; }

env_value() { sed -n "s/^$1=\([^ #]*\).*/\1/p" .env | head -1; }
HOST=$(env_value PSYCHRON_MQTT_HOST)
[ -n "$HOST" ] && [ "$HOST" != "127.0.0.1" ] || {
  echo "PSYCHRON_MQTT_HOST in .env must be this machine's LAN address; the phone cannot reach 127.0.0.1" >&2
  exit 1
}
# Optional: an address that reaches this host from mobile data. The phone tries
# it first whenever it is not on Wi-Fi. It must already be in the broker's
# certificate, or every connection to it fails hostname verification — which is
# checked here, before anything is pushed, rather than discovered on the phone.
REMOTE=$(env_value PSYCHRON_MQTT_REMOTE_HOST)
HOSTS="$HOST"
if [ -n "$REMOTE" ]; then
  if ! openssl x509 -in certs/broker.crt -noout -ext subjectAltName | grep -qE "(IP Address|DNS):${REMOTE//./\\.}(,|$)"; then
    echo "certs/broker.crt does not name $REMOTE: run ./make-certs.sh, then docker compose restart broker" >&2
    exit 1
  fi
  HOSTS="$HOST,$REMOTE"
fi

# The key as issued is SEC1 ("EC PRIVATE KEY"). Java's KeyFactory reads PKCS#8, so
# it is converted here rather than parsed by hand on the phone.
openssl pkcs8 -topk8 -nocrypt -in "certs/$DEVICE.key" -out "$TMP_LOCAL/client.pk8"
cp "certs/ca.crt" "$TMP_LOCAL/ca.crt"
cp "certs/$DEVICE.crt" "$TMP_LOCAL/client.crt"
printf 'hosts=%s\nport=8883\ndevice=%s\n' "$HOSTS" "$DEVICE" > "$TMP_LOCAL/node.properties"

"$ADB" shell mkdir -p "$TMP_REMOTE"
for f in ca.crt client.crt client.pk8 node.properties; do
  "$ADB" push "$TMP_LOCAL/$f" "$TMP_REMOTE/$f" >/dev/null
done
"$ADB" shell run-as "$PKG" sh -c "'mkdir -p files/certs && cp $TMP_REMOTE/* files/certs/ && chmod 600 files/certs/*'"

echo "  provisioned $DEVICE -> ${HOSTS//,/ | } :8883"
"$ADB" shell run-as "$PKG" ls -l files/certs
