#!/usr/bin/env bash
set -Eeuo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$deploy_dir/.env"
backup_root="${QUOTATION_DATA_ROOT:-/srv/ahmln-data/quotation-app}/backups/supplier-removal"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$backup_root/$timestamp"
temporary="$backup_root/.$timestamp.tmp.$$"
case "$target" in /srv/ahmln-data/quotation-app/backups/supplier-removal/*) ;; *) echo "ERROR: unexpected supplier export target" >&2; exit 1;; esac
test ! -e "$target" -a ! -e "$temporary" || { echo "ERROR: supplier export target already exists" >&2; exit 1; }
mkdir -p "$temporary"
chmod 0700 "$temporary"
cleanup(){ status=$?; if [[ -d "$temporary" ]]; then rm -rf -- "$temporary"; fi; exit "$status"; }
trap cleanup EXIT

database="${QUOTATION_DB_NAME:-quotation_prod}"
database_user="${QUOTATION_DB_USER:-quotation_app}"
compose=(docker compose --project-name quotation-prod --env-file "$deploy_dir/.env" -f "$deploy_dir/docker-compose.yml")
psql=("${compose[@]}" exec -T quotation-postgres psql --no-psqlrc --set ON_ERROR_STOP=1 --dbname="$database" --username="$database_user")

table_presence="$("${psql[@]}" --tuples-only --no-align --command "select (to_regclass('public.supplier') is not null)::int || ',' || (to_regclass('public.supplier_product') is not null)::int")"
if [[ "$table_presence" == "0,0" ]]; then
  rmdir -- "$temporary"
  trap - EXIT
  echo "not-applicable-already-removed"
  exit 0
fi
[[ "$table_presence" == "1,1" ]] || { echo "ERROR: supplier tables are only partially present" >&2; exit 1; }

supplier_count="$("${psql[@]}" --tuples-only --no-align --command 'select count(*) from supplier')"
link_count="$("${psql[@]}" --tuples-only --no-align --command 'select count(*) from supplier_product')"
linked_product_count="$("${psql[@]}" --tuples-only --no-align --command 'select count(distinct product_id) from supplier_product')"
[[ "$supplier_count" =~ ^[0-9]+$ && "$link_count" =~ ^[0-9]+$ && "$linked_product_count" =~ ^[0-9]+$ ]] || { echo "ERROR: invalid supplier export counts" >&2; exit 1; }

"${psql[@]}" --command "\copy (select id,replace(replace(code,E'\r',E'\\\\r'),E'\n',E'\\\\n') code,replace(replace(name,E'\r',E'\\\\r'),E'\n',E'\\\\n') name,replace(replace(contact_name,E'\r',E'\\\\r'),E'\n',E'\\\\n') contact_name,replace(replace(phone,E'\r',E'\\\\r'),E'\n',E'\\\\n') phone,replace(replace(platform,E'\r',E'\\\\r'),E'\n',E'\\\\n') platform,replace(replace(category,E'\r',E'\\\\r'),E'\n',E'\\\\n') category,replace(replace(settlement_terms,E'\r',E'\\\\r'),E'\n',E'\\\\n') settlement_terms,lead_time_days,rating,enabled,version,created_at,updated_at from supplier order by id) to stdout with (format csv, header true)" > "$temporary/supplier.csv"
"${psql[@]}" --command "\copy (select link.id,link.supplier_id,link.product_id,replace(replace(product.sku,E'\r',E'\\\\r'),E'\n',E'\\\\n') product_sku,replace(replace(link.supplier_sku,E'\r',E'\\\\r'),E'\n',E'\\\\n') supplier_sku,link.enabled,link.created_at,link.updated_at from supplier_product link join purchase_product product on product.id=link.product_id order by link.id) to stdout with (format csv, header true)" > "$temporary/supplier_product.csv"

exported_supplier_count="$(( $(wc -l < "$temporary/supplier.csv") - 1 ))"
exported_link_count="$(( $(wc -l < "$temporary/supplier_product.csv") - 1 ))"
[[ "$exported_supplier_count" -eq "$supplier_count" ]] || { echo "ERROR: supplier export row count mismatch" >&2; exit 1; }
[[ "$exported_link_count" -eq "$link_count" ]] || { echo "ERROR: supplier product export row count mismatch" >&2; exit 1; }

printf '{\n  "exportedAt": "%s",\n  "supplierRows": %s,\n  "supplierProductRows": %s,\n  "linkedPurchaseProducts": %s\n}\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$supplier_count" "$link_count" "$linked_product_count" > "$temporary/manifest.json"
(
  cd "$temporary"
  sha256sum supplier.csv supplier_product.csv manifest.json > SHA256SUMS
  sha256sum -c SHA256SUMS >/dev/null
)
chmod 0600 "$temporary"/*
mv -- "$temporary" "$target"
trap - EXIT
echo "$target"
