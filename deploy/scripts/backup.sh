#!/usr/bin/env bash
set -Eeuo pipefail
deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$deploy_dir/.env"
backup_root="${QUOTATION_DATA_ROOT:-/srv/ahmln-data/quotation-app}/backups"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$backup_root/${timestamp}-full"
temporary="$backup_root/.${timestamp}-full.tmp.$$"
case "$target" in /srv/ahmln-data/quotation-app/backups/*-full) ;; *) echo "ERROR: unexpected full backup target" >&2; exit 1;; esac
test ! -e "$target" -a ! -e "$temporary" || { echo "ERROR: backup target already exists" >&2; exit 1; }
mkdir -p "$temporary"
cleanup(){ status=$?; if [[ -d "$temporary" ]]; then rm -rf -- "$temporary"; fi; exit "$status"; }
trap cleanup EXIT

docker compose --project-name quotation-prod --env-file "$deploy_dir/.env" -f "$deploy_dir/docker-compose.yml" exec -T quotation-postgres \
  pg_dump --format=custom --no-owner --dbname=quotation_prod --username=quotation_app > "$temporary/quotation_prod.dump"
test -s "$temporary/quotation_prod.dump" || { echo "ERROR: PostgreSQL backup is empty" >&2; exit 1; }
mkdir -p "$temporary/objects/quotation-assets"
docker run --rm --network quotation-internal \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp \
  -e "MC_HOST_quotation=http://${QUOTATION_MINIO_ACCESS_KEY}:${QUOTATION_MINIO_SECRET_KEY}@quotation-minio:9000" \
  -v "$temporary/objects:/backup" minio/mc:RELEASE.2025-07-21T05-28-08Z \
  mirror --overwrite quotation/quotation-assets /backup/quotation-assets >&2
(
  cd "$temporary/objects"
  find quotation-assets -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum > ../quotation-assets.sha256
)
docker compose --project-name quotation-prod --env-file "$deploy_dir/.env" -f "$deploy_dir/docker-compose.yml" config --images > "$temporary/images.txt"
(
  cd "$temporary"
  sha256sum quotation_prod.dump quotation-assets.sha256 images.txt > SHA256SUMS
  sha256sum -c SHA256SUMS >/dev/null
)
mv -- "$temporary" "$target"
trap - EXIT

mapfile -t completed_backups < <(find "$backup_root" -mindepth 1 -maxdepth 1 -type d -name '*-full' -print | LC_ALL=C sort)
if (( ${#completed_backups[@]} > 2 )); then
  remove_count=$((${#completed_backups[@]} - 2))
  for ((index=0; index<remove_count; index++)); do
    old_backup="${completed_backups[$index]}"
    case "$old_backup" in /srv/ahmln-data/quotation-app/backups/*-full) ;; *) echo "ERROR: refusing unexpected retention target" >&2; exit 1;; esac
    test -f "$old_backup/SHA256SUMS" || { echo "ERROR: refusing to remove unverified backup $old_backup" >&2; exit 1; }
    (cd "$old_backup" && sha256sum -c SHA256SUMS >/dev/null)
    rm -rf -- "$old_backup"
  done
fi
echo "$target"
