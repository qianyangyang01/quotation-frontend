#!/usr/bin/env bash
set -Eeuo pipefail
deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$deploy_dir/.env"
root="${QUOTATION_DATA_ROOT:-/srv/ahmln-data/quotation-app}"
previous="$(readlink -f "$root/previous" 2>/dev/null || true)"
current="$(readlink -f "$root/current" 2>/dev/null || true)"
case "$previous" in "$root/releases/"*) ;; *) echo "ERROR: valid quotation previous release not found" >&2; exit 1;; esac
case "$current" in "$root/releases/"*) ;; *) echo "ERROR: valid quotation current release not found" >&2; exit 1;; esac
test -f "$previous/deploy/.env" -a -f "$previous/deploy/docker-compose.yml" || { echo "ERROR: previous release package is incomplete" >&2; exit 1; }
backup_path="$(bash "$current/deploy/scripts/backup.sh")"
# The current image contains the retention helper even when the rollback image predates it.
frontend_image="$(docker inspect --format '{{.Config.Image}}' quotation-prod-quotation-frontend-1)"
bash "$current/deploy/scripts/retain-frontend-assets.sh" "$frontend_image"
export QUOTATION_FRONTEND_ASSETS_VOLUME="${QUOTATION_FRONTEND_ASSETS_VOLUME:-quotation-frontend-assets}"
docker compose --project-name quotation-prod --env-file "$previous/deploy/.env" -f "$previous/deploy/docker-compose.yml" \
  -f "$deploy_dir/docker-compose.assets.yml" up -d --remove-orphans
bash "$previous/deploy/scripts/container-healthcheck.sh" "$previous/deploy"
bash "$previous/deploy/scripts/healthcheck.sh"
ln -sfn "$previous" "$root/current"
if [[ "$current" == "$root/releases/"* ]]; then ln -sfn "$current" "$root/previous"; fi
echo "Quotation containers rolled back to $(basename "$previous"); backup=$backup_path; no external service was addressed"
