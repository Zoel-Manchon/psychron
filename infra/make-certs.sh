#!/usr/bin/env bash
# Issues the CA and the leaf certificates for mutual TLS.
#
# Elliptic curve throughout, not RSA: a P-256 handshake is several times cheaper
# on the ESP32 and the certificates are a fraction of the size, which matters on
# a board already at 76% of its flash.
#
# Deliberately a handful of openssl calls rather than an integration with the
# Keystone CA. Getting mTLS working and wiring up a certificate authority at the
# same time means two unfamiliar failure modes at once; Keystone replaces this
# script once the transport is known good.
#
# Safe to re-run: it never overwrites an existing key, because reissuing the CA
# would silently invalidate every certificate already deployed to a device.
set -euo pipefail
cd "$(dirname "$0")"

CERTS=certs
DAYS_CA=3650
DAYS_LEAF=730

# The node connects to the broker by IP, so that IP has to appear in the server
# certificate's subjectAltName. A certificate without it fails hostname
# verification with an error that points at the certificate rather than at the
# missing name, which is a slow thing to debug on a device with no console.
# Taken from .env, which bootstrap.sh writes and the ingestion service already
# reads, so the address the node dials and the address inside the certificate
# come from one place and cannot disagree. Override with BROKER_IP=... to issue
# a certificate for a host other than this one.
env_host=$(sed -n 's/^PSYCHRON_MQTT_HOST=\([^ #]*\).*/\1/p' .env 2>/dev/null | head -1)
BROKER_IP="${BROKER_IP:-${env_host:-127.0.0.1}}"

if [ "$BROKER_IP" = "127.0.0.1" ]; then
  echo "  NOTE: issuing for 127.0.0.1. A node on the LAN cannot use this," >&2
  echo "        and the failure appears as a TLS handshake error rather than" >&2
  echo "        as a wrong address. Set PSYCHRON_MQTT_HOST in .env first." >&2
fi

mkdir -p "$CERTS"
chmod 700 "$CERTS" 2>/dev/null || true

gen_key() { openssl ecparam -name prime256v1 -genkey -noout -out "$1"; }

cat > "$CERTS/ext.cnf" <<EOF
[server]
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[alt_names]
IP.1 = ${BROKER_IP}
IP.2 = 127.0.0.1
DNS.1 = localhost
DNS.2 = psychron-broker

[client]
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = clientAuth
EOF

# ── certificate authority ────────────────────────────────────────────────────
if [ -f "$CERTS/ca.key" ]; then
  echo "  CA already exists, leaving it alone"
else
  gen_key "$CERTS/ca.key"
  # keyUsage is not optional. A CA certificate that declares CA:TRUE but never
  # says keyCertSign is accepted by lenient stacks — mosquitto and mbedTLS take
  # it — and rejected outright by strict ones, which is a failure that only
  # appears when some future client happens to be one of the strict ones.
  # pathlen:0 says this CA signs leaves and never another CA.
  openssl req -x509 -new -key "$CERTS/ca.key" -sha256 -days "$DAYS_CA" \
    -out "$CERTS/ca.crt" -subj "/O=psychron/CN=psychron-root-ca" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"
  echo "  issued CA, valid ${DAYS_CA} days"
fi

# ── broker ───────────────────────────────────────────────────────────────────
if [ -f "$CERTS/broker.key" ]; then
  echo "  broker certificate already exists, leaving it alone"
else
  gen_key "$CERTS/broker.key"
  openssl req -new -key "$CERTS/broker.key" -out "$CERTS/broker.csr" \
    -subj "/O=psychron/CN=psychron-broker"
  openssl x509 -req -in "$CERTS/broker.csr" -CA "$CERTS/ca.crt" -CAkey "$CERTS/ca.key" \
    -CAcreateserial -out "$CERTS/broker.crt" -days "$DAYS_LEAF" -sha256 \
    -extfile "$CERTS/ext.cnf" -extensions server
  rm -f "$CERTS/broker.csr"
  echo "  issued broker certificate for IP ${BROKER_IP}"
fi

# ── clients ──────────────────────────────────────────────────────────────────
# The CN becomes the MQTT username: mosquitto is configured with
# use_identity_as_username, so these names have to match the ACL exactly.
issue_client() {
  local cn="$1"
  if [ -f "$CERTS/$cn.key" ]; then
    echo "  client '$cn' already exists, leaving it alone"
    return
  fi
  gen_key "$CERTS/$cn.key"
  openssl req -new -key "$CERTS/$cn.key" -out "$CERTS/$cn.csr" -subj "/O=psychron/CN=$cn"
  openssl x509 -req -in "$CERTS/$cn.csr" -CA "$CERTS/ca.crt" -CAkey "$CERTS/ca.key" \
    -CAcreateserial -out "$CERTS/$cn.crt" -days "$DAYS_LEAF" -sha256 \
    -extfile "$CERTS/ext.cnf" -extensions client
  rm -f "$CERTS/$cn.csr"
  echo "  issued client certificate CN=$cn"
}

issue_client esp32-01
issue_client ingest
# The Android node. Its key is generated here and copied onto the phone, which is
# exactly the weakness the ESP32 has too: a device key that has existed outside
# the device. The next step replaces this with a key born inside StrongBox that
# never leaves it, and a CSR signed here instead.
issue_client phone-01

# The firmware server gets a server certificate of its own rather than reusing
# the broker's: two services on one key means a compromise of either is a
# compromise of both, and they have different lifetimes.
if [ -f "$CERTS/fwserver.key" ]; then
  echo "  firmware server certificate already exists, leaving it alone"
else
  gen_key "$CERTS/fwserver.key"
  openssl req -new -key "$CERTS/fwserver.key" -out "$CERTS/fwserver.csr"     -subj "/O=psychron/CN=psychron-fwserver"
  openssl x509 -req -in "$CERTS/fwserver.csr" -CA "$CERTS/ca.crt" -CAkey "$CERTS/ca.key"     -CAcreateserial -out "$CERTS/fwserver.crt" -days "$DAYS_LEAF" -sha256     -extfile "$CERTS/ext.cnf" -extensions server
  rm -f "$CERTS/fwserver.csr"
  echo "  issued firmware server certificate for IP ${BROKER_IP}"
fi

# The web edge. One certificate authority for the whole system rather than
# Caddy's own internal CA: a browser has to be told to trust something either
# way, and telling it to trust one root that already guards the broker and the
# firmware server is a smaller ask than trusting a second one.
if [ -f "$CERTS/web.key" ]; then
  echo "  web certificate already exists, leaving it alone"
else
  gen_key "$CERTS/web.key"
  openssl req -new -key "$CERTS/web.key" -out "$CERTS/web.csr"     -subj "/O=psychron/CN=psychron-web"
  openssl x509 -req -in "$CERTS/web.csr" -CA "$CERTS/ca.crt" -CAkey "$CERTS/ca.key"     -CAcreateserial -out "$CERTS/web.crt" -days "$DAYS_LEAF" -sha256     -extfile "$CERTS/ext.cnf" -extensions server
  rm -f "$CERTS/web.csr"
  echo "  issued web certificate for IP ${BROKER_IP}"
fi

# Mosquitto refuses to start on a key it considers world readable.
chmod 640 "$CERTS"/*.key 2>/dev/null || true

echo
echo "Certificates in infra/certs (gitignored). The private keys never leave it."
openssl x509 -in "$CERTS/broker.crt" -noout -subject -dates -ext subjectAltName | sed 's/^/  /'
