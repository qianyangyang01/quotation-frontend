#!/usr/bin/env bash
set -Eeuo pipefail
deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$deploy_dir/.env"
hourly_root="${QUOTATION_DATA_ROOT:-/srv/ahmln-data/quotation-app}/backups/hourly"
case "$hourly_root" in /srv/ahmln-data/quotation-app/backups/hourly) ;; *) echo "ERROR: unexpected hourly backup root" >&2; exit 1;; esac
target="$hourly_root/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$target"
docker compose --project-name quotation-prod --env-file "$deploy_dir/.env" -f "$deploy_dir/docker-compose.yml" exec -T quotation-postgres \
  pg_dump --format=custom --no-owner --dbname=quotation_prod --username=quotation_app > "$target/quotation_prod.dump"
sha256sum "$target/quotation_prod.dump" > "$target/SHA256SUMS"
find "$hourly_root" -mindepth 2 -type f -mtime +7 -delete
find "$hourly_root" -mindepth 1 -type d -empty -delete
