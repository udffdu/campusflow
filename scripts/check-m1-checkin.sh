#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/resolve-python.sh
./services/platform/mvnw -B "$@" -f services/seats/pom.xml verify
if [ ! -d apps/web/node_modules ]; then (cd apps/web && npm ci); fi
(cd apps/web && npm test && npm run build)
bash scripts/prepare-m1-http.sh "$@"
"$PYTHON" scripts/check_m1_personal.py
"$PYTHON" scripts/check_repository.py
