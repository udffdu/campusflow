#!/usr/bin/env bash

# Source this file after changing to the repository root. It deliberately does
# not change shell options so callers keep control of their own execution mode.
if [ -n "${CF_PYTHON_EXECUTABLE:-}" ]; then
  PYTHON="$CF_PYTHON_EXECUTABLE"
elif [ -n "${CF_PYTHON:-}" ]; then
  # Backward compatibility for the original personal-preview variable.
  PYTHON="$CF_PYTHON"
else
  PYTHON=
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 \
        && "$candidate" -c 'import sys; assert sys.version_info >= (3, 11)' >/dev/null 2>&1; then
      PYTHON="$candidate"
      break
    fi
  done
  if [ -z "$PYTHON" ]; then
    echo '需要 Python 3.11 或更高版本；可通过 CF_PYTHON_EXECUTABLE 指定解释器。' >&2
    return 1 2>/dev/null || exit 1
  fi
fi

if ! "$PYTHON" -c 'import sys; assert sys.version_info >= (3, 11)' >/dev/null 2>&1; then
  echo "解释器 $PYTHON 不可用或版本低于 Python 3.11；请通过 CF_PYTHON_EXECUTABLE 指定解释器。" >&2
  return 1 2>/dev/null || exit 1
fi
