#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
source "$MINI_ROOT/difftest/deps.lock"
source "$MINI_ROOT/scripts/dependency.sh"
NEMU_DIR="$DEPS_DIR/nemu-ref"
mkdir -p "$DEPS_DIR" "$BUILD_DIR/ref"
exec 7>"$DEPS_DIR/.nemu.lock"
flock 7

NEMU_PATCHES=("$MINI_ROOT/difftest/patches/0001-rv64-without-c.patch")
prepare_source NEMU "$NEMU_URL" "$NEMU_COMMIT" "$NEMU_DIR" "${NEMU_PATCHES[@]}"
export NEMU_HOME="$NEMU_DIR"
STAMP="$NEMU_DIR/build/mini-ref.sha256"
if [[ -f "$NEMU_DIR/build/riscv64-nemu-interpreter-so" && -f "$STAMP" ]] &&
   sha256sum --check --status "$STAMP"; then
  echo "Reusing pinned NEMU reference in $NEMU_DIR/build"
  install -m 755 "$NEMU_DIR/build/riscv64-nemu-interpreter-so" "$BUILD_DIR/ref/"
  exit 0
fi
# Always configure deterministically, including after a previous manual build.
make -C "$NEMU_DIR" riscv64-nutshell-ref_defconfig
sed -i \
  -e 's/^# CONFIG_RV_NO_C is not set/CONFIG_RV_NO_C=y/' \
  -e 's/^CONFIG_RESET_FROM_MMIO=y/# CONFIG_RESET_FROM_MMIO is not set/' \
  -e 's/^CONFIG_RV_PMA_CHECK=y/# CONFIG_RV_PMA_CHECK is not set/' \
  -e 's/^CONFIG_STORE_LOG=y/# CONFIG_STORE_LOG is not set/' \
  -e 's/^# CONFIG_TVAL_EX_II is not set/CONFIG_TVAL_EX_II=y/' \
  -e 's/^# CONFIG_DIFFTEST_STORE_COMMIT is not set/CONFIG_DIFFTEST_STORE_COMMIT=y/' \
  -e 's/^CONFIG_MSIZE=.*/CONFIG_MSIZE=0x1000000/' \
  "$NEMU_DIR/.config"
(
  cd "$NEMU_DIR"
  tools/kconfig/build/conf --olddefconfig Kconfig
  tools/kconfig/build/conf --syncconfig Kconfig
)
make -C "$NEMU_DIR" -j "${JOBS:-8}"
sha256sum "$MINI_ROOT/scripts/build-ref.sh" "$MINI_ROOT/difftest/deps.lock" \
  "$MINI_ROOT/scripts/dependency.sh" "${NEMU_PATCHES[@]}" "$NEMU_DIR/.config" \
  "$NEMU_DIR/build/riscv64-nemu-interpreter-so" > "$STAMP"
install -m 755 "$NEMU_DIR/build/riscv64-nemu-interpreter-so" "$BUILD_DIR/ref/"
