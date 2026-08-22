#!/usr/bin/env bash
set -Eeuo pipefail
curl -fsS --max-time 15 https://vip.ahmln.com/ >/dev/null
health="$(curl -fsS --max-time 15 https://vip.ahmln.com/api/actuator/health/readiness)"
grep -q '"status":"UP"' <<< "$health"
if curl -fsS --connect-timeout 3 http://vip.ahmln.com:8088/actuator/health >/dev/null 2>&1; then
  echo "ERROR: public port 8088 is reachable" >&2; exit 1
fi
headers="$(curl -fsSI --max-time 15 https://vip.ahmln.com/)"
grep -qi '^x-content-type-options: nosniff' <<< "$headers"
echo "Quotation public health, TLS route, headers and closed 8088 checks passed"
