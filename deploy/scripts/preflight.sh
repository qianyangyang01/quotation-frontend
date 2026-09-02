#!/usr/bin/env bash
set -Eeuo pipefail
deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$deploy_dir/.env"
data_root="${QUOTATION_DATA_ROOT:-/srv/ahmln-data/quotation-app}"

for command in docker curl openssl df awk grep ss getent; do command -v "$command" >/dev/null || { echo "ERROR: missing $command" >&2; exit 1; }; done
docker compose version >/dev/null
docker network inspect ahmln-edge >/dev/null || { echo "ERROR: external network ahmln-edge does not exist" >&2; exit 1; }
getent hosts vip.ahmln.com >/dev/null || { echo "ERROR: vip.ahmln.com does not resolve" >&2; exit 1; }
case "$data_root" in /srv/ahmln-data/quotation-app) ;; *) echo "ERROR: unexpected data root: $data_root" >&2; exit 1;; esac
mkdir -p "$data_root"/{releases,tmp,backups}

available_kb="$(df -Pk "$data_root" | awk 'NR==2 {print $4}')"
total_kb="$(df -Pk "$data_root" | awk 'NR==2 {print $2}')"
if (( available_kb * 100 < total_kb * 25 )); then echo "ERROR: data filesystem has less than 25% free space" >&2; exit 1; fi
if ss -lnt | awk '{print $4}' | grep -Eq '(^|:)8088$'; then echo "ERROR: host port 8088 is already listening; it must remain unexposed" >&2; exit 1; fi
for key in QUOTATION_POSTGRES_PASSWORD QUOTATION_REDIS_PASSWORD QUOTATION_MINIO_ACCESS_KEY QUOTATION_MINIO_SECRET_KEY QUOTATION_BOOTSTRAP_ADMIN_PASSWORD; do
  value="${!key:-}"; (( ${#value} >= 20 )) || { echo "ERROR: $key is missing or too short" >&2; exit 1; }
  [[ "$value" != replace-* ]] || { echo "ERROR: $key still uses an example value" >&2; exit 1; }
done
echo "Quotation production preflight passed"
