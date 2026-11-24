#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
[[ $BUILD_DIR == "$MINI_ROOT/build" || $BUILD_DIR == "$MINI_ROOT/build/"* ]] ||
  die "refusing to clean this BUILD_DIR: $BUILD_DIR"
[[ $DEPS_DIR == "$MINI_ROOT/.deps" ]] ||
  die "clean only removes $MINI_ROOT/.deps; remove custom caches explicitly"
rm -rf -- "$BUILD_DIR" "$MINI_ROOT/target" "$MINI_ROOT/project"  "$DEPS_DIR"
echo "Removed CPU build products and the repository dependency cache. App builds and comparison results are retained."
