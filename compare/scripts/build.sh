#!/usr/bin/env bash
set -euo pipefail
COMPARE_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export BUILD_DIR=${BUILD_DIR:-"$COMPARE_DIR/build"}
source "$COMPARE_DIR/../scripts/common.sh"
source "$COMPARE_DIR/scripts/config.sh"
require_tool "$VERILATOR"
mkdir -p "$BUILD_DIR"
exec 8>"$BUILD_DIR/.compare.lock"
flock 8
bash "$COMPARE_DIR/scripts/fetch-nutshell.sh"
bash "$COMPARE_DIR/scripts/build-mini.sh"
bash "$COMPARE_DIR/scripts/build-nutshell.sh"
echo
echo "Comparison simulators: $BUILD_DIR/{mini-emu,nutshell-$NUTSHELL_CORE-emu}"
echo "Build an app in $MINI_ROOT/apps/<name>/, then run here: make run APP=<name> NUTSHELL_CORE=$NUTSHELL_CORE"
