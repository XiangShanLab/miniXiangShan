#!/usr/bin/env bash
# Shared paths and tools for CPU, app dependencies, and comparison builds.
MINI_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
BUILD_DIR=$(realpath -m -- "${BUILD_DIR:-$MINI_ROOT/build}")
DEPS_DIR=$(realpath -m -- "${DEPS_DIR:-$MINI_ROOT/.deps}")
export BUILD_DIR DEPS_DIR
export RTL_DIR="$BUILD_DIR/rtl"
export JOBS=${JOBS:-8}

export VERILATOR=${VERILATOR:-verilator}
export MILL=${MILL:-mill}

die() { echo "ERROR: $*" >&2; exit 1; }
require_tool() { command -v "$1" >/dev/null 2>&1 || die "required tool not found: $1"; }
[[ $JOBS =~ ^[1-9][0-9]*$ ]] || die "JOBS must be a positive integer"

fingerprint() { python3 "$MINI_ROOT/scripts/fingerprint.py" "$@"; }

find_sbt() {
  if [[ -z ${SBT:-} ]]; then
    SBT=$(command -v sbt || true)
    [[ -n $SBT ]] || SBT="$HOME/sbt/bin/sbt"
  fi
  require_tool "$SBT"
  export SBT
}
