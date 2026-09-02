#!/usr/bin/env bash
set -Eeuo pipefail
if [[ $# -ne 1 ]]; then echo "Usage: restore.sh /srv/ahmln-data/quotation-app/backups/TIMESTAMP" >&2; exit 2; fi
target="$(readlink -f "$1")"
case "$target" in /srv/ahmln-data/quotation-app/backups/*) ;; *) echo "ERROR: restore path is outside quotation backups" >&2; exit 1;; esac
test -f "$target/quotation_prod.dump" || { echo "ERROR: database dump missing" >&2; exit 1; }
deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$deploy_dir/.env"
docker compose --project-name quotation-prod --env-file "$deploy_dir/.env" -f "$deploy_dir/docker-compose.yml" exec -T quotation-postgres \
  pg_restore --clean --if-exists --no-owner --exit-on-error --dbname=quotation_prod --username=quotation_app < "$target/quotation_prod.dump"
echo "Quotation database restored; no non-quotation container was addressed"
