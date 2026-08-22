#!/usr/bin/env bash
set -Eeuo pipefail
root="${QUOTATION_DATA_ROOT:-/srv/ahmln-data/quotation-app}"
case "$root" in /srv/ahmln-data/quotation-app) ;; *) echo "ERROR: unexpected quotation root" >&2; exit 1;; esac
test -f "$root/current/deploy/scripts/backup.sh" || { echo "ERROR: current quotation release is incomplete" >&2; exit 1; }
command -v crontab >/dev/null || { echo "ERROR: crontab is unavailable" >&2; exit 1; }
mkdir -p "$root/backups"
temporary="$(mktemp)"
cleaned="$(mktemp)"
trap 'rm -f -- "$temporary" "$cleaned"' EXIT
crontab -l > "$temporary" 2>/dev/null || true
awk '
  /^# BEGIN ahmln-quotation-managed$/ {skip=1; next}
  /^# END ahmln-quotation-managed$/ {skip=0; next}
  !skip {print}
' "$temporary" > "$cleaned"
cat >> "$cleaned" <<'EOF'

# BEGIN ahmln-quotation-managed
25 * * * * cd /srv/ahmln-data/quotation-app/current/deploy && bash scripts/backup-database.sh >> /srv/ahmln-data/quotation-app/backups/cron.log 2>&1
45 2 * * * cd /srv/ahmln-data/quotation-app/current/deploy && bash scripts/backup-object-manifest.sh >> /srv/ahmln-data/quotation-app/backups/cron.log 2>&1
0 5 * * 0 cd /srv/ahmln-data/quotation-app/current/deploy && bash scripts/backup.sh >> /srv/ahmln-data/quotation-app/backups/cron.log 2>&1
# END ahmln-quotation-managed
EOF
crontab "$cleaned"
echo "Quotation backup cron installed without changing other managed blocks"
