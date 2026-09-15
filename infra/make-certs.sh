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

# Under Git Bash, an argument that starts with a slash is rewritten into a Windows
# path before a native program sees it, and -subj "/O=psychron/CN=..." arrives at
# openssl as "C:/Program Files/Git/O=psychron/...". Every path below is relative,
# so conversion has nothing useful left to do here.
export MSYS_NO_PATHCONV=1

CERTS=certs
DAYS_CA=3650
DAYS_LEAF=730

# `openssl ca` keeps a database of what it has revoked. Only revocation uses it:
# certificates are still issued with `openssl x509 -req`, and the database learns
# of one only when it is revoked, which `openssl ca -revoke` accepts.
ensure_ca_database() {
  [ -f "$CERTS/index.txt" ] || : > "$CERTS/index.txt"
  [ -f "$CERTS/crlnumber" ] || echo 1000 > "$CERTS/crlnumber"
  cat > "$CERTS/ca.cnf" <<EOF
[ca]
default_ca = psychron

[psychron]
database         = $CERTS/index.txt
crlnumber        = $CERTS/crlnumber
certificate      = $CERTS/ca.crt
private_key      = $CERTS/ca.key
default_md       = sha256
default_crl_days = $DAYS_CA
EOF
}

# ./make-certs.sh revoke <name>
#
# Revokes certs/<name>.crt, regenerates the list and moves the certificate and its
# key into certs.old-<date>/, which .gitignore already keeps out of the tree. Used
# when a phone enrols a hardware key: the file key it had before existed on the host
# and in the phone's storage, and must stop working, not merely stop being used.
if [ "${1:-}" = "revoke" ]; then
  name="${2:?usage: ./make-certs.sh revoke <name>}"
  [ -f "$CERTS/$name.crt" ] || { echo "no $CERTS/$name.crt to revoke" >&2; exit 1; }
  ensure_ca_database
  openssl ca -config "$CERTS/ca.cnf" -revoke "$CERTS/$name.crt" 2>&1 | grep -v "^Using configuration" | sed 's/^/  /'
  openssl ca -config "$CERTS/ca.cnf" -gencrl -out "$CERTS/crl.pem" 2>/dev/null
  archive="certs.old-$(date +%Y%m%d)"
  mkdir -p "$archive"
  mv "$CERTS/$name.crt" "$archive/"
  [ -f "$CERTS/$name.key" ] && mv "$CERTS/$name.key" "$archive/"
  echo "  revoked $name, moved to $archive/. The broker reads the list at start:"
  echo "    docker compose restart broker"
  exit 0
fi

# The node connects to the broker by IP, so that IP has to appear in the server
# certificate's subjectAltName. A certificate without it fails hostname
# verification with an error that points at the certificate rather than at the
# missing name, which is a slow thing to debug on a device with no console.
# Taken from .env, which bootstrap.sh writes and the ingestion service already
# reads, so the address the node dials and the address inside the certificate
# come from one place and cannot disagree. Override with BROKER_IP=... to issue
# a certificate for a host other than this one.
env_value() { sed -n "s/^$1=\([^ #]*\).*/\1/p" .env 2>/dev/null | head -1; }
BROKER_IP="${BROKER_IP:-$(env_value PSYCHRON_MQTT_HOST)}"
BROKER_IP="${BROKER_IP:-127.0.0.1}"

# A second address for nodes away from the LAN — a Tailscale or WireGuard address,
# or a DNS name that reaches this host from outside. Optional. A node verifies the
# broker's name against whichever address it dialled, so every address a node may
# use has to be in the certificate, not only the one it uses at home.
REMOTE_HOST="${REMOTE_HOST:-$(env_value PSYCHRON_MQTT_REMOTE_HOST)}"

if [ "$BROKER_IP" = "127.0.0.1" ]; then
  echo "  NOTE: issuing for 127.0.0.1. A node on the LAN cannot use this," >&2
  echo "        and the failure appears as a TLS handshake error rather than" >&2
  echo "        as a wrong address. Set PSYCHRON_MQTT_HOST in .env first." >&2
fi

mkdir -p "$CERTS"
chmod 700 "$CERTS" 2>/dev/null || true

gen_key() { openssl ecparam -name prime256v1 -genkey -noout -out "$1"; }

is_ip() { printf '%s' "$1" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$'; }

# The names every server certificate must carry, one "IP:x" or "DNS:x" per line,
# in the spelling openssl prints them in.
wanted_sans() {
  echo "IP Address:${BROKER_IP}"
  echo "IP Address:127.0.0.1"
  echo "DNS:localhost"
  echo "DNS:psychron-broker"
  if [ -n "$REMOTE_HOST" ]; then
    if is_ip "$REMOTE_HOST"; then echo "IP Address:${REMOTE_HOST}"; else echo "DNS:${REMOTE_HOST}"; fi
  fi
}

remote_san=""
if [ -n "$REMOTE_HOST" ]; then
  if is_ip "$REMOTE_HOST"; then remote_san="IP.3 = ${REMOTE_HOST}"; else remote_san="DNS.3 = ${REMOTE_HOST}"; fi
fi

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
${remote_san}

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

# ── server certificates ──────────────────────────────────────────────────────
# Issued once, and re-signed — never re-keyed — when the set of names changes.
# Re-signing is safe where re-keying is not: every node trusts the CA rather than
# a particular leaf, so a broker certificate with one more address in it is
# accepted by the ESP32 without reflashing and by the phone without reprovisioning.
issue_server() {
  local name="$1" cn="$2"
  local key="$CERTS/$name.key" crt="$CERTS/$name.crt"
  if [ -f "$key" ] && [ -f "$crt" ]; then
    local have missing
    have=$(openssl x509 -in "$crt" -noout -ext subjectAltName 2>/dev/null | tail -n +2 | tr ',' '\n' | sed 's/^ *//')
    missing=$(wanted_sans | while read -r san; do grep -qxF "$san" <<<"$have" || echo "$san"; done)
    if [ -z "$missing" ]; then
      echo "  $name certificate already covers every address, leaving it alone"
      return
    fi
    echo "  $name certificate lacks: $(echo $missing) — re-signing with the existing key"
  else
    gen_key "$key"
  fi
  openssl req -new -key "$key" -out "$CERTS/$name.csr" -subj "/O=psychron/CN=$cn"
  openssl x509 -req -in "$CERTS/$name.csr" -CA "$CERTS/ca.crt" -CAkey "$CERTS/ca.key" \
    -CAcreateserial -out "$crt" -days "$DAYS_LEAF" -sha256 \
    -extfile "$CERTS/ext.cnf" -extensions server
  rm -f "$CERTS/$name.csr"
  echo "  issued $name certificate for ${BROKER_IP}${REMOTE_HOST:+ and $REMOTE_HOST}"
  RESIGNED="${RESIGNED:-} $name"
}

issue_server broker psychron-broker

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
# The Android node, until it enrols. A phone generates its own key in secure
# hardware and provision-phone.sh signs its request into phone-01.device.crt; from
# then on the host holds no key for it, and none is issued here again. Before that,
# a host-issued key is what lets the simulator and a phone without enrolment work.
if [ -f "$CERTS/phone-01.device.crt" ]; then
  echo "  client 'phone-01' is enrolled with a hardware key, issuing nothing for it"
else
  issue_client phone-01
fi

# The firmware server gets a server certificate of its own rather than reusing
# the broker's: two services on one key means a compromise of either is a
# compromise of both, and they have different lifetimes.
issue_server fwserver psychron-fwserver

# The web edge. One certificate authority for the whole system rather than
# Caddy's own internal CA: a browser has to be told to trust something either
# way, and telling it to trust one root that already guards the broker and the
# firmware server is a smaller ask than trusting a second one.
issue_server web psychron-web

# Mosquitto refuses to start on a key it considers world readable.
chmod 640 "$CERTS"/*.key 2>/dev/null || true

# The revocation list, regenerated on every run so it always exists: the broker
# refuses to start without the file it is configured to check, and a list that
# expired would refuse every client at once — hence ten years, the CA's lifetime.
ensure_ca_database
openssl ca -config "$CERTS/ca.cnf" -gencrl -out "$CERTS/crl.pem" 2>/dev/null
echo "  revocation list: $(openssl crl -in "$CERTS/crl.pem" -noout -text | grep -c 'Serial Number') revoked"

echo
echo "Certificates in infra/certs (gitignored). The private keys never leave it."
openssl x509 -in "$CERTS/broker.crt" -noout -subject -dates -ext subjectAltName | sed 's/^/  /'
if [ -n "${RESIGNED:-}" ] && docker compose ps --status running 2>/dev/null | grep -qE 'broker|web|fwserver'; then
  echo
  echo "  Re-signed:${RESIGNED}. Running services still hold the old certificate:"
  echo "    docker compose restart broker web fwserver"
fi
