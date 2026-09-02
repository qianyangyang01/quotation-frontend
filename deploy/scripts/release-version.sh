#!/usr/bin/env bash
set -Eeuo pipefail
if [[ $# -ne 1 || ! "$1" =~ ^quotation-[0-9]{4}\.[0-9]{2}\.[0-9]{2}-[0-9]{2}$ ]]; then
  echo "Usage: release-version.sh quotation-YYYY.MM.DD-NN" >&2; exit 2
fi
release="$1"
deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository_dir="$(cd "$deploy_dir/.." && pwd)"
source "$deploy_dir/.env"
root="${QUOTATION_DATA_ROOT:-/srv/ahmln-data/quotation-app}"
release_dir="$root/releases/$release"
release_deploy_dir="$release_dir/deploy"
test ! -e "$release_dir" || { echo "ERROR: release already exists" >&2; exit 1; }

git -C "$repository_dir" diff --quiet
git -C "$repository_dir" diff --cached --quiet
[[ -z "$(git -C "$repository_dir" status --porcelain)" ]] || {
  echo "ERROR: production releases must be built from a clean quotation commit" >&2
  exit 1
}

bash "$deploy_dir/scripts/preflight.sh"
existing_containers="$(docker ps -aq --filter label=com.docker.compose.project=quotation-prod)"
current_before="$(readlink -f "$root/current" 2>/dev/null || true)"
if [[ "$current_before" == "$root/releases/"* && -f "$current_before/deploy/scripts/backup.sh" ]]; then
  supplier_export_output="$(bash "$deploy_dir/scripts/export-supplier-master-data.sh")"
  supplier_export_path="$(tail -n 1 <<< "$supplier_export_output")"
  if [[ "$supplier_export_path" != "not-applicable-already-removed" ]]; then
    case "$supplier_export_path" in "$root/backups/supplier-removal/"*) ;; *) echo "ERROR: invalid supplier export path" >&2; exit 1;; esac
    test -d "$supplier_export_path" -a -f "$supplier_export_path/SHA256SUMS" || { echo "ERROR: verified supplier export is incomplete" >&2; exit 1; }
  fi
  supplier_record_export_output="$(bash "$deploy_dir/scripts/export-supplier-records.sh")"
  supplier_record_export_path="$(tail -n 1 <<< "$supplier_record_export_output")"
  case "$supplier_record_export_path" in "$root/backups/supplier-records/"*) ;; *) echo "ERROR: invalid supplier record export path" >&2; exit 1;; esac
  test -d "$supplier_record_export_path" -a -f "$supplier_record_export_path/SHA256SUMS" || { echo "ERROR: verified supplier record export is incomplete" >&2; exit 1; }
  # MinIO may emit progress before the final path. Keep that output visible,
  # but persist only the final verified directory produced by this candidate.
  backup_output="$(bash "$deploy_dir/scripts/backup.sh")"
  backup_path="$(tail -n 1 <<< "$backup_output")"
  if [[ "$backup_output" == *$'\n'* ]]; then sed '$d' <<< "$backup_output" >&2; fi
  [[ "$backup_path" != *$'\n'* ]] || { echo "ERROR: backup path must be a single line" >&2; exit 1; }
  case "$backup_path" in "$root/backups/"*-full) ;; *) echo "ERROR: invalid quotation backup path" >&2; exit 1;; esac
  test -d "$backup_path" -a -f "$backup_path/SHA256SUMS" || { echo "ERROR: verified quotation backup is incomplete" >&2; exit 1; }
elif [[ -n "$existing_containers" ]]; then
  echo "ERROR: quotation containers exist without a valid current release; refusing an untracked upgrade" >&2
  exit 1
else
  backup_path="not-applicable-initial-release"
  supplier_export_path="not-applicable-initial-release"
  supplier_record_export_path="not-applicable-initial-release"
fi
docker build --pull --label "com.milano.quotation.release=$release" \
  -t "quotation-backend:$release" "$repository_dir/backend"
docker build --pull --label "com.milano.quotation.release=$release" \
  -t "quotation-frontend:$release" "$repository_dir"
mkdir -p "$release_deploy_dir"
cp "$deploy_dir/docker-compose.yml" "$release_deploy_dir/docker-compose.yml"
cp "$deploy_dir/README.md" "$release_deploy_dir/README.md"
cp -R "$deploy_dir/scripts" "$release_deploy_dir/scripts"
cp -R "$deploy_dir/nginx" "$release_deploy_dir/nginx"
cp -R "$deploy_dir/systemd" "$release_deploy_dir/systemd"
cp "$deploy_dir/.env" "$release_deploy_dir/.env"
chmod 0600 "$release_deploy_dir/.env"
sed -i "s/^QUOTATION_RELEASE=.*/QUOTATION_RELEASE=$release/" "$release_deploy_dir/.env"
docker compose --project-name quotation-prod --env-file "$release_deploy_dir/.env" -f "$release_deploy_dir/docker-compose.yml" \
  pull quotation-postgres quotation-redis quotation-minio
docker compose --project-name quotation-prod --env-file "$release_deploy_dir/.env" -f "$release_deploy_dir/docker-compose.yml" up -d --remove-orphans
bash "$release_deploy_dir/scripts/container-healthcheck.sh" "$release_deploy_dir"

if [[ "$current_before" == "$root/releases/"* ]]; then ln -sfn "$current_before" "$root/previous"; fi
ln -sfn "$release_dir" "$root/current"
printf '%s\n' \
  "release=$release" \
  "git_sha=$(git -C "$repository_dir" rev-parse HEAD)" \
  "supplier_export=$supplier_export_path" \
  "supplier_record_export=$supplier_record_export_path" \
  "backup=$backup_path" \
  "deployed_at=$(date -u +%FT%TZ)" > "$release_dir/manifest.txt"
echo "Released $release"
