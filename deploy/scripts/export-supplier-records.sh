#!/usr/bin/env bash
set -Eeuo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$deploy_dir/.env"
backup_root="${QUOTATION_DATA_ROOT:-/srv/ahmln-data/quotation-app}/backups/supplier-records"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$backup_root/$timestamp"
temporary="$backup_root/.$timestamp.tmp.$$"
case "$target" in /srv/ahmln-data/quotation-app/backups/supplier-records/*) ;; *) echo "ERROR: unexpected supplier record export target" >&2; exit 1;; esac
test ! -e "$target" -a ! -e "$temporary" || { echo "ERROR: supplier record export target already exists" >&2; exit 1; }
mkdir -p "$temporary"
chmod 0700 "$temporary"
cleanup(){ status=$?; if [[ -d "$temporary" ]]; then rm -rf -- "$temporary"; fi; exit "$status"; }
trap cleanup EXIT

database="${QUOTATION_DB_NAME:-quotation_prod}"
database_user="${QUOTATION_DB_USER:-quotation_app}"
compose=(docker compose --project-name quotation-prod --env-file "$deploy_dir/.env" -f "$deploy_dir/docker-compose.yml")
psql=("${compose[@]}" exec -T quotation-postgres psql --no-psqlrc --set ON_ERROR_STOP=1 --dbname="$database" --username="$database_user")

table_present="$("${psql[@]}" --tuples-only --no-align --command "select (to_regclass('public.supplier_record') is not null)::int")"
[[ "$table_present" == "1" ]] || { echo "ERROR: supplier_record table is missing" >&2; exit 1; }

record_count="$("${psql[@]}" --tuples-only --no-align --command 'select count(*) from supplier_record')"
[[ "$record_count" =~ ^[0-9]+$ ]] || { echo "ERROR: invalid supplier record count" >&2; exit 1; }

"${psql[@]}" --command "COPY (select * from supplier_record order by id) TO STDOUT WITH (FORMAT csv, HEADER true)" > "$temporary/supplier_record.csv"
"${psql[@]}" --command "COPY (select id from supplier_record order by id) TO STDOUT WITH (FORMAT csv, HEADER true)" > "$temporary/supplier_record_ids.csv"

exported_count="$(( $(wc -l < "$temporary/supplier_record_ids.csv") - 1 ))"
[[ "$exported_count" -eq "$record_count" ]] || { echo "ERROR: supplier record export row count mismatch" >&2; exit 1; }

printf '{\n  "exportedAt": "%s",\n  "supplierRecordRows": %s\n}\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$record_count" > "$temporary/manifest.json"
(
  cd "$temporary"
  sha256sum supplier_record.csv supplier_record_ids.csv manifest.json > SHA256SUMS
  sha256sum -c SHA256SUMS >/dev/null
)
chmod 0600 "$temporary"/*
mv -- "$temporary" "$target"
trap - EXIT
echo "$target"
