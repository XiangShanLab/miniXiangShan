#!/usr/bin/env bash
set -euo pipefail
APP_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$APP_DIR/../../scripts/common.sh"
source "$MINI_ROOT/scripts/dependency.sh"
source "$APP_DIR/deps.lock"
mkdir -p "$DEPS_DIR"
exec 6>"$DEPS_DIR/.coremark.lock"
flock 6
core_dir="$DEPS_DIR/coremark"
prepare_source CoreMark "$COREMARK_URL" "$COREMARK_COMMIT" "$core_dir" \
  "$APP_DIR/report-results.patch"
# Check the pinned pristine files against the recorded upstream checksums.
python3 - "$core_dir" "$APP_DIR/checksums.sha256" <<'PY'
import hashlib
from pathlib import Path
import subprocess
import sys

directory, checksums = sys.argv[1:]
for line in Path(checksums).read_text().splitlines():
    expected, name = line.split()
    data = subprocess.check_output(["git", "-C", directory, "show", "HEAD:" + name])
    if hashlib.sha256(data).hexdigest() != expected:
        sys.exit(f"ERROR: upstream CoreMark checksum mismatch: {name}")
PY
