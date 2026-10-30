#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/resolve-python.sh
if [ ! -d apps/web/node_modules ]; then (cd apps/web && npm ci); fi
if [ "${CF_MAVEN_OFFLINE:-0}" = 1 ]; then bash scripts/prepare-m1-http.sh -o; else bash scripts/prepare-m1-http.sh; fi
exec "$PYTHON" scripts/check_m1_submit.py --demo "$@"
