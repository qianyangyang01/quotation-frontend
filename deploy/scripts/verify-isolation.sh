#!/usr/bin/env bash
set -Eeuo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

if [[ "${ALLOW_DIRTY_QUOTATION_CHECKOUT:-false}" != "true" ]] && [[ -n "$(git status --porcelain)" ]]; then
  echo "ERROR: checkout is not clean" >&2; exit 1
fi
blocked='Documents[/\\]kaohe|jdbc:[^ ]*(kaohe|training)|container_name:[[:space:]]*(kaohe|training)|bucket[^:]*:[[:space:]]*(kaohe|training)'
if grep -RInE "$blocked" src backend deploy Dockerfile nginx.conf --exclude-dir=target --exclude=verify-isolation.sh; then
  echo "ERROR: a forbidden cross-system reference was found" >&2; exit 1
fi
compose="deploy/docker-compose.yml"
grep -q 'name: quotation-prod' "$compose"
grep -q 'quotation_prod' "$compose"
grep -q 'quotation_app' "$compose"
grep -q 'quotation-assets' "$compose"
if grep -qE '^[[:space:]]*ports:' "$compose"; then
  echo "ERROR: production compose must not publish application or data ports" >&2; exit 1
fi
echo "Isolation source and naming checks passed"
