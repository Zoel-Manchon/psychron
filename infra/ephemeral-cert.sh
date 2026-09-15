#!/usr/bin/env bash
# ephemeral-cert.sh <cn> <dir>
#
# A client certificate valid for one day, with its key, in <dir>. For the transport
# checks and the simulator once a phone has enrolled a hardware key: the host then
# holds no long-lived key for that identity, and should not grow one just so a test
# can speak as the phone. Delete <dir> when done; the certificate expires by itself.
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1

cn="${1:?usage: ephemeral-cert.sh <cn> <dir>}"
out="${2:?usage: ephemeral-cert.sh <cn> <dir>}"
[ -f certs/ca.key ] && [ -f certs/ext.cnf ] || { echo "run ./make-certs.sh first" >&2; exit 1; }

mkdir -p "$out"
openssl ecparam -name prime256v1 -genkey -noout -out "$out/$cn.key"
openssl req -new -key "$out/$cn.key" -out "$out/$cn.csr" -subj "/O=psychron/CN=$cn"
openssl x509 -req -in "$out/$cn.csr" -CA certs/ca.crt -CAkey certs/ca.key -CAcreateserial \
  -out "$out/$cn.crt" -days 1 -sha256 -extfile certs/ext.cnf -extensions client 2>/dev/null
rm -f "$out/$cn.csr"
chmod 640 "$out/$cn.key" 2>/dev/null || true
