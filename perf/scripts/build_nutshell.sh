#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PERF_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
ROOT=$(cd -- "$PERF_DIR/.." && pwd)

NUTSHELL_DIR=${NUTSHELL_DIR:-"$ROOT/.deps/NutShell"}
BUILD_DIR=${PERF_BUILD_DIR:-"$PERF_DIR/.build"}
RTL_DIR=${RTL_DIR:-"$ROOT/build/rtl"}
NUTSHELL_RTL_DIR="$(realpath -m -- "$RTL_DIR")/perf/nutshell"
OBJ_DIR="$BUILD_DIR/obj-nutshell"
HEX="$BUILD_DIR/coremark/coremark-rv64.hex"
MILL_BIN=${MILL_BIN:-mill}

[[ -d "$NUTSHELL_DIR/src" ]] || {
  echo "ERROR: missing NutShell source at $NUTSHELL_DIR; run fetch_deps.sh" >&2
  exit 1
}
[[ -f "$NUTSHELL_DIR/src/main/scala/sim/NutShellPerfTop.scala" ]] || {
  echo "ERROR: NutShell performance adapter is not installed" >&2
  exit 1
}
[[ -f "$HEX" ]] || {
  echo "ERROR: missing $HEX; build CoreMark first" >&2
  exit 1
}

mkdir -p "$BUILD_DIR" "$NUTSHELL_RTL_DIR" "$OBJ_DIR"

echo "Elaborating NutShell performance RTL"
(cd "$NUTSHELL_DIR" && "$MILL_BIN" -i generator.test.runMain top.TopMain \
  --target-dir "$NUTSHELL_RTL_DIR" BOARD=perf CORE=ooo --split-verilog)

echo "Building NutShell Verilator model"
OBJCACHE= verilator --cc --exe --build -j 8 \
  --top-module NutShellPerfTop \
  -Wno-fatal -Wno-lint -Wno-style \
  -O2 --x-assign fast --x-initial fast \
  --Mdir "$OBJ_DIR" \
  -DDRAM_INIT="\"$HEX\"" \
  "$NUTSHELL_RTL_DIR"/*.sv \
  "$PERF_DIR/platform/rtl/Axi4PerfPlatform.v" \
  "$PERF_DIR/platform/tb/nutshell_main.cpp" \
  -o nutshell-perf

echo "NutShell simulator: $OBJ_DIR/nutshell-perf"
