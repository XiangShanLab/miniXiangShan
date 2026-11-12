#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PERF_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
ROOT=$(cd -- "$PERF_DIR/.." && pwd)

BUILD_DIR=${PERF_BUILD_DIR:-"$PERF_DIR/.build"}
OBJ_DIR="$BUILD_DIR/obj-mini"
HEX="$BUILD_DIR/coremark/coremark-rv64.hex"
export RTL_DIR=${RTL_DIR:-"$ROOT/build/rtl"}
RTL_DIR=$(realpath -m -- "$RTL_DIR")
MINI_RTL_DIR="$RTL_DIR/perf/mini"
SBT_RUNTIME_DIR=${SBT_RUNTIME_DIR:-"${TMPDIR:-/tmp}/miniXiangShan-perf-sbt-${UID:-0}"}
SBT_BIN=${SBT_BIN:-}

if [[ -z "$SBT_BIN" ]]; then
  if command -v sbt >/dev/null 2>&1; then
    SBT_BIN=$(command -v sbt)
  elif [[ -x "$HOME/sbt/bin/sbt" ]]; then
    SBT_BIN="$HOME/sbt/bin/sbt"
  else
    echo "ERROR: sbt not found; set SBT_BIN or add sbt to PATH" >&2
    exit 1
  fi
fi
[[ -f "$HEX" ]] || {
  echo "ERROR: missing $HEX; build CoreMark first" >&2
  exit 1
}

mkdir -p "$BUILD_DIR" "$OBJ_DIR" "$SBT_RUNTIME_DIR"
chmod 700 "$SBT_RUNTIME_DIR"

rtl_is_current() {
  [[ ${FORCE_RTL_ELAB:-0} != 1 ]] && [[ -f "$MINI_RTL_DIR/MiniPerfTop.v" ]] || return 1
  ! find "$ROOT/src/main/scala" "$ROOT/build.sbt" "$ROOT/project/build.properties" \
    -type f -newer "$MINI_RTL_DIR/MiniPerfTop.v" -print -quit | grep -q .
}

if rtl_is_current; then
  echo "Reusing generated miniXiangShan RTL in $MINI_RTL_DIR"
else
  echo "Elaborating miniXiangShan performance RTL"
  (cd "$ROOT" && XDG_RUNTIME_DIR="$SBT_RUNTIME_DIR" \
    "$SBT_BIN" --server -batch 'runMain minixiangshan.CoreGen perf')
fi

echo "Building miniXiangShan Verilator model"
OBJCACHE= verilator --cc --exe --build -j 8 \
  --assert \
  --top-module MiniPerfTop \
  -Wno-fatal -Wno-lint -Wno-style \
  -O2 --x-assign fast --x-initial fast \
  --Mdir "$OBJ_DIR" \
  -DDRAM_INIT="\"$HEX\"" \
  "$MINI_RTL_DIR"/*.v \
  "$PERF_DIR/platform/rtl/Axi4PerfPlatform.v" \
  "$PERF_DIR/platform/tb/mini_main.cpp" \
  -o miniperf-rv64

echo "miniXiangShan simulator: $OBJ_DIR/miniperf-rv64"
