#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Submission suite now includes real D/M1 history and repeat checks; retains original read-only regression.
exec bash scripts/check-m1-submit.sh "$@"
