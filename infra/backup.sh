#!/usr/bin/env bash
# Dump the database.
#
# The whole argument for this project is that the long record cannot be
# recreated: room temperature can be measured again, elapsed calendar time
# cannot. Every design decision so far has been justified by protecting that
# record — the boot anchor, the offline buffer, the idempotency index, the
# refusal to interpolate over gaps — and until now there was no copy of it
# anywhere. One container removed and the argument was worth nothing.
#
# Custom format, so a restore can be selective and parallel. Compressed by
# pg_dump itself rather than piped, so a truncated pipe cannot produce a file
# that looks complete.
set -euo pipefail
cd "$(dirname "$0")"

# shellcheck disable=SC1091
set -a; . ./.env; set +a

OUT="${PSYCHRON_BACKUP_DIR:-./backups}"
KEEP="${PSYCHRON_BACKUP_KEEP:-14}"
STAMP=$(date +%Y%m%d-%H%M%S)
FILE="$OUT/psychron-$STAMP.dump"

mkdir -p "$OUT"

# Written to a temporary name and moved into place only on success: a dump
# interrupted halfway must never be mistaken for a usable one.
docker compose exec -T db pg_dump -U psychron -d psychron -Fc --compress=6 > "$FILE.partial"
mv "$FILE.partial" "$FILE"

size=$(wc -c < "$FILE")
echo "  wrote $FILE  ($((size / 1024)) KiB)"

# A dump nobody has ever restored is a hope, not a backup. This proves the file
# is readable and lists what it holds, which is the cheapest possible check and
# still catches the common failures: empty, truncated, wrong database.
tables=$(docker compose exec -T db pg_restore --list < "$FILE" | grep -c "TABLE DATA" || true)
echo "  verified: $tables table(s) of data inside"
[ "$tables" -ge 4 ] || { echo "  refusing to keep a dump with almost nothing in it" >&2; exit 1; }

# Retention, oldest first. Keeping every dump forever fills the disk that the
# database is also on, which turns a backup into an outage.
mapfile -t old < <(ls -1t "$OUT"/psychron-*.dump 2>/dev/null | tail -n +$((KEEP + 1)))
for f in "${old[@]:-}"; do
  [ -n "$f" ] && rm -f "$f" && echo "  pruned $(basename "$f")"
done

echo
echo "Restore with:"
echo "  docker compose exec -T db pg_restore -U psychron -d psychron --clean --if-exists < $FILE"
