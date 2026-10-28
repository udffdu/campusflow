#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ ! -d apps/web/node_modules ]; then (cd apps/web && npm ci); fi
CF_MAVEN_ARGS=()
if [ "${CF_MAVEN_OFFLINE:-0}" = 1 ]; then CF_MAVEN_ARGS=(-o); fi
./services/platform/mvnw -B "${CF_MAVEN_ARGS[@]}" -f services/seats/pom.xml -DskipTests package
bash scripts/prepare-m1-http.sh "${CF_MAVEN_ARGS[@]}"
exec python3 scripts/check_m1_personal.py --preview "$@"
