#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Test-only accelerator: a normal UI booking is moved past the check-in deadline
# only inside the disposable fixture database. No production endpoint or rule is bypassed.
exec bash scripts/start-m1-reminder-preview.sh --manual-no-show "$@"
