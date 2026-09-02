#!/usr/bin/env bash
set -Eeuo pipefail
release_dir="${1:-}"
[[ -n "$release_dir" && -f "$release_dir/docker-compose.yml" && -f "$release_dir/.env" ]] || {
  echo "Usage: container-healthcheck.sh RELEASE_DIRECTORY" >&2
  exit 2
}

compose=(docker compose --project-name quotation-prod --env-file "$release_dir/.env" -f "$release_dir/docker-compose.yml")
services=(quotation-postgres quotation-redis quotation-minio quotation-backend quotation-frontend)
deadline=$((SECONDS + 300))
while (( SECONDS < deadline )); do
  all_healthy=true
  for service in "${services[@]}"; do
    container_id="$("${compose[@]}" ps -q "$service")"
    [[ -n "$container_id" ]] || { echo "ERROR: $service has no container" >&2; exit 1; }
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
    if [[ "$status" == "unhealthy" || "$status" == "exited" || "$status" == "dead" ]]; then
      echo "ERROR: $service is $status" >&2
      exit 1
    fi
    [[ "$status" == "healthy" ]] || all_healthy=false
  done
  if [[ "$all_healthy" == "true" ]]; then
    echo "All five quotation containers are healthy"
    exit 0
  fi
  sleep 5
done
echo "ERROR: quotation containers did not become healthy within 300 seconds" >&2
exit 1
