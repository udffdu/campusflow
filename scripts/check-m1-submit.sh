#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./services/platform/mvnw -B "$@" -f services/seats/pom.xml verify
if [ ! -d apps/web/node_modules ]; then (cd apps/web && npm ci); fi
(cd apps/web && npm test && npm run build)
bash scripts/prepare-m1-http.sh "$@"
# python3 scripts/check_m1_http.py
# python3 scripts/check_m1_submit.py
# python3 scripts/check_repository.py
python scripts/check_m1_http.py
python scripts/check_m1_submit.py
python scripts/check_repository.py