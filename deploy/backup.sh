#!/bin/bash
# Nightly encrypted pg_dump at 02:00 Riyadh, kept for BACKUP_KEEP_DAYS days.
# Copy /backups off the VM (object storage in the same Saudi region) — see docs/go-live.md.
set -uo pipefail
apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq gnupg >/dev/null 2>&1
while true; do
  now=$(TZ=Asia/Riyadh date +%H%M)
  if [ "$now" = "0200" ]; then
    f="/backups/waslah-$(TZ=Asia/Riyadh date +%F).sql.gz.gpg"
    if pg_dump --format=plain --no-owner | gzip -9 | gpg --batch --yes --symmetric --cipher-algo AES256 \
        --passphrase "$BACKUP_PASSPHRASE" -o "$f"; then
      echo "backup ok: $f ($(du -h "$f" | cut -f1))"
    else
      echo "backup FAILED" >&2
    fi
    find /backups -name 'waslah-*.sql.gz.gpg' -mtime +"$BACKUP_KEEP_DAYS" -delete
    sleep 61
  fi
  sleep 30
done
