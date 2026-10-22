#!/bin/sh
# Nightly pg_dump of the StandPOS database (custom format), keeping the newest $BACKUP_KEEP dumps in /backups.
#
#   BACKUP_AT    time of day, UTC, HH:MM (default 02:30)
#   BACKUP_KEEP  number of dumps to keep (default 30)
#
# One-off dump: docker compose -f deploy/docker-compose.yml run --rm backup --now
# Restore:      pg_restore --clean --if-exists -d standpos /backups/standpos-<timestamp>.dump
set -e

DIR=/backups
KEEP=${BACKUP_KEEP:-30}
AT=${BACKUP_AT:-02:30}

backup() {
    stamp=$(date -u +%Y%m%dT%H%M%SZ)
    file="$DIR/standpos-$stamp.dump"
    pg_dump --format=custom --file="$file.partial"
    mv "$file.partial" "$file"
    ls -1t "$DIR"/standpos-*.dump | tail -n +"$((KEEP + 1))" | xargs -r rm -f
    echo "$(date -u +%FT%TZ) backup written: $file"
}

if [ "${1:-}" = "--now" ]; then
    backup
    exit 0
fi

echo "Nightly backup at $AT UTC, keeping $KEEP dumps"
while true; do
    now=$(date -u +%s)
    next=$(date -u -d "$(date -u +%Y-%m-%d) $AT:00" +%s)
    if [ "$next" -le "$now" ]; then
        next=$((next + 86400))
    fi
    sleep $((next - now))
    backup || echo "$(date -u +%FT%TZ) backup FAILED" >&2
done
