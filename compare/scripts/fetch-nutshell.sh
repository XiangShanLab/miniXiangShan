#!/usr/bin/env bash
set -euo pipefail
COMPARE_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export BUILD_DIR=${BUILD_DIR:-"$COMPARE_DIR/build"}
source "$COMPARE_DIR/../scripts/common.sh"
source "$MINI_ROOT/scripts/dependency.sh"
source "$COMPARE_DIR/nutshell.lock"
mkdir -p "$DEPS_DIR" "$BUILD_DIR"
exec 6>"$DEPS_DIR/.nutshell.lock"
flock 6
directory="$DEPS_DIR/NutShell"
prepare_source NutShell "$NUTSHELL_URL" "$NUTSHELL_COMMIT" "$directory" \
  "$COMPARE_DIR/patches/nutshell/"*.patch
git -C "$directory" submodule update --init --recursive --depth 1
adapter="$directory/src/main/scala/sim/NutShellPerfTop.scala"
adapter_stamp="$directory/.git/mini-adapter.sha256"
if [[ -f $adapter ]] && ! cmp -s "$COMPARE_DIR/platform/nutshell/NutShellPerfTop.scala" "$adapter" &&
   ! { [[ -f $adapter_stamp ]] && sha256sum --check --status "$adapter_stamp"; }; then
  die "existing NutShell adapter differs from this checkout; preserved: $adapter"
fi
install -m 644 "$COMPARE_DIR/platform/nutshell/NutShellPerfTop.scala" "$adapter"
sha256sum "$adapter" > "$adapter_stamp"
{
  echo "NutShell repository: $NUTSHELL_URL"
  echo "NutShell revision: $(git -C "$directory" rev-parse HEAD)"
  echo "NutShell difftest revision: $(git -C "$directory/difftest" rev-parse HEAD)"
  echo "Adaptation fingerprint: $(fingerprint "$COMPARE_DIR/patches" "$COMPARE_DIR/platform/nutshell")"
} > "$BUILD_DIR/dependencies.txt"
