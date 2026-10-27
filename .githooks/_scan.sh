#!/bin/sh
# Shared body for .githooks/pre-commit and .githooks/pre-push.
#
#   git config core.hooksPath .githooks
#
# Exits non-zero when the scanner reports a finding. If no Python interpreter
# is available the check is skipped rather than blocking all work; CI still
# enforces it.

set -u

stage="${1:-commit}"

py=""
for candidate in python3 python py; do
    # Windows ships a python3.exe alias that only opens the Store and fails,
    # so probe the interpreter instead of trusting `command -v`.
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "pass" >/dev/null 2>&1; then
        py="$candidate"
        break
    fi
done

if [ -z "$py" ]; then
    echo "$stage: no python interpreter found, skipping secret scan" >&2
    exit 0
fi

if ! "$py" scripts/check-secrets.py; then
    echo "" >&2
    echo "$stage: secret scan failed, aborting." >&2
    echo "$stage: run 'python3 scripts/check-secrets.py --verbose' for details." >&2
    exit 1
fi

exit 0
