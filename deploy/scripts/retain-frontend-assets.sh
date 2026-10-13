#!/usr/bin/env bash
set -Eeuo pipefail
if [[ $# != 1 ]]; then echo "Usage: retain-frontend-assets.sh <new-frontend-image>" >&2; exit 2; fi
image="$1"
deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$deploy_dir/.env"
volume="${QUOTATION_FRONTEND_ASSETS_VOLUME:-quotation-frontend-assets}"
container="$(docker ps -aq --filter label=com.docker.compose.project=quotation-prod --filter label=com.docker.compose.service=quotation-frontend)"
if [[ -z "$container" ]]; then exit 0; fi
[[ "$container" != *$'\n'* ]] || { echo "Expected one quotation frontend" >&2; exit 1; }
docker volume create "$volume" >/dev/null
current_volume="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/usr/share/nginx/html/assets"}}{{.Name}}{{end}}{{end}}' "$container")"
if [[ "$current_volume" != "$volume" ]]; then
  stage="$(mktemp -d)"
  trap 'rm -rf -- "$stage"' EXIT
  mkdir "$stage/assets"
  # Seed the previous version on first upgrade; later releases already share this archive.
  docker cp "$container:/usr/share/nginx/html/assets/." "$stage/assets/"
  docker run --rm --network none --entrypoint /opt/quotation/retain-assets.sh \
    --mount "type=bind,src=$stage/assets,dst=/seed,readonly" \
    --mount "type=volume,src=$volume,dst=/retained" \
    "$image" /seed /retained
fi
# Validate/publish the candidate before downtime; a hash collision must not replace the running app.
docker run --rm --network none --entrypoint /opt/quotation/retain-assets.sh \
  --mount "type=volume,src=$volume,dst=/retained" \
  "$image" /opt/quotation/assets /retained
