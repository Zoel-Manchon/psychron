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

# The API token. The API mints one on first start if there is none, but compose
# mounts .env read-only, so there it could only crash. Appended to an existing
# .env too: adding an entry that is missing overwrites nothing.
if ! grep -q '^PSYCHRON_API_TOKEN=.' .env; then
  echo "PSYCHRON_API_TOKEN=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')" >> .env
  echo "  added an API token to .env"
fi

# The API and ingestion run as uid 10001 (backend/Dockerfile). Where the bind mount
# keeps Linux permissions — Docker Engine, or Docker Desktop with the repository
# inside WSL — the 600 above shuts them out and both exit on a PermissionError.
# An ACL lets that one user read the file without making the database password
# readable by anyone else.
if command -v setfacl >/dev/null 2>&1; then
  setfacl -m u:10001:r .env
elif [ "$(uname -s)" = Linux ]; then
  echo "  NOTE: setfacl is missing, so the api and ingest containers cannot read" >&2
  echo "        .env. Install it (sudo apt install acl) and re-run." >&2
fi

# The broker has no passwords any more: the only way in is a certificate signed
# by the CA, and those come from make-certs.sh. A credential that exists but is
# unused is one somebody eventually trusts by mistake.

echo
echo "Next:  ./make-certs.sh && docker compose up -d"
