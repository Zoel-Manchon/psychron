#!/usr/bin/env bash
# Generates the credentials the stack needs. Safe to re-run: it never overwrites
# a file that already exists, so it cannot silently invalidate a running node.
set -euo pipefail

cd "$(dirname "$0")"

gen_pw() { openssl rand -base64 24 | tr -d '\n/+=' | cut -c1-24; }

if [ -f .env ]; then
  echo "  .env already exists, leaving it alone"
else
  {
    echo "POSTGRES_PASSWORD=$(gen_pw)"
    # The address of this machine on the LAN, not loopback. A host running its
    # own mosquitto bound to 127.0.0.1 wins over Docker's wildcard bind, so
    # loopback can quietly reach the wrong broker; and the node connects by the
    # LAN address anyway, which is what the broker certificate is issued for.
    echo "PSYCHRON_MQTT_HOST=127.0.0.1   # set this to the LAN address of this host"
  } > .env
  chmod 600 .env
  echo "  wrote .env with a fresh database password"
fi

# The broker has no passwords any more: the only way in is a certificate signed
# by the CA, and those come from make-certs.sh. A credential that exists but is
# unused is one somebody eventually trusts by mistake.

echo
echo "Next:  ./make-certs.sh && docker compose up -d"
