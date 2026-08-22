#!/usr/bin/env bash
set -Eeuo pipefail
deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$deploy_dir/.env"
manifest_root="${QUOTATION_DATA_ROOT:-/srv/ahmln-data/quotation-app}/backups/manifests"
case "$manifest_root" in /srv/ahmln-data/quotation-app/backups/manifests) ;; *) echo "ERROR: unexpected manifest backup root" >&2; exit 1;; esac
target="$manifest_root/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$target"
docker run --rm --network quotation-internal \
  -e "MC_HOST_quotation=http://${QUOTATION_MINIO_ACCESS_KEY}:${QUOTATION_MINIO_SECRET_KEY}@quotation-minio:9000" \
  minio/mc:RELEASE.2025-07-21T05-28-08Z find quotation/quotation-assets --json > "$target/quotation-assets.jsonl"
sha256sum "$target/quotation-assets.jsonl" > "$target/SHA256SUMS"
find "$manifest_root" -mindepth 2 -type f -mtime +35 -delete
find "$manifest_root" -mindepth 1 -type d -empty -delete
