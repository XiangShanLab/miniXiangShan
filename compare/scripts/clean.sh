#!/usr/bin/env bash
set -euo pipefail
COMPARE_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export BUILD_DIR=${BUILD_DIR:-"$COMPARE_DIR/build"}
source "$COMPARE_DIR/../scripts/common.sh"
[[ $BUILD_DIR == "$COMPARE_DIR/build" || $BUILD_DIR == "$COMPARE_DIR/build/"* ]] ||
  die "refusing to clean this BUILD_DIR: $BUILD_DIR"
rm -rf -- "$BUILD_DIR"
echo "Removed comparison build products. Apps, results and dependencies are retained."
