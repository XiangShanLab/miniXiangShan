#!/usr/bin/env bash
set -euo pipefail
COMPARE_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export BUILD_DIR=${BUILD_DIR:-"$COMPARE_DIR/build"}
source "$COMPARE_DIR/../scripts/common.sh"
require_tool "$MILL"
directory="$DEPS_DIR/NutShell"
output="$RTL_DIR/compare/nutshell"
mkdir -p "$output"
mill_version=$(cd "$directory" && "$MILL" -i --version 2>&1)
key=$(fingerprint --value "$MILL,$mill_version" \
  "$directory/src" "$directory/build.mill" "$directory/difftest/src/main" \
  "$COMPARE_DIR/scripts/build-nutshell.sh")
if [[ ! -f "$output/NutShellPerfTop.sv" || ! -f "$output/.source-hash" ||
      $(<"$output/.source-hash") != "$key" ]]; then
  (cd "$directory" && "$MILL" -i generator.test.runMain top.TopMain \
    --target-dir "$output" BOARD=perf CORE=ooo --split-verilog)
  printf '%s\n' "$key" > "$output/.source-hash"
else
  echo "Reusing NutShell RTL: $output"
fi
obj="$BUILD_DIR/obj-nutshell"
OBJCACHE= "$VERILATOR" --cc --exe --build -j "$JOBS" \
  --top-module NutShellPerfTop -Wno-fatal -Wno-lint -Wno-style \
  -O2 --x-assign fast --x-initial fast --Mdir "$obj" \
  "$output/"*.sv "$COMPARE_DIR/platform/rtl/Axi4PerfPlatform.v" \
  "$COMPARE_DIR/platform/tb/nutshell_main.cpp" -o nutshell-emu
install -m 755 "$obj/nutshell-emu" "$BUILD_DIR/nutshell-emu"
