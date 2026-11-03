#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Test-only accelerator: after a user creates a reservation through the normal UI,
# the shared isolated preview inserts both reminder events into that temporary outbox.
# Production code, production configuration and the real reservation database are untouched.
exec bash scripts/start-m1-reminder-preview.sh --manual-booking "$@"
