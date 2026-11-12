#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PERF_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
ROOT=$(cd -- "$PERF_DIR/.." && pwd)

CORE_DIR=${COREMARK_DIR:-"$ROOT/.deps/coremark"}
PORT_DIR="$PERF_DIR/platform/coremark"
BUILD_DIR=${COREMARK_BUILD_DIR:-"$PERF_DIR/.build/coremark"}

RISCV_CC=${RISCV_CC:-riscv64-unknown-elf-gcc}
RISCV_OBJCOPY=${RISCV_OBJCOPY:-riscv64-unknown-elf-objcopy}
RISCV_SIZE=${RISCV_SIZE:-riscv64-unknown-elf-size}
HOST_CC=${HOST_CC:-cc}
ITERATIONS=${ITERATIONS:-10}
TOTAL_DATA_SIZE=${TOTAL_DATA_SIZE:-2000}

required=(
  "$CORE_DIR/core_main.c"
  "$CORE_DIR/core_list_join.c"
  "$CORE_DIR/core_matrix.c"
  "$CORE_DIR/core_state.c"
  "$CORE_DIR/core_util.c"
  "$CORE_DIR/coremark.h"
  "$PORT_DIR/core_portme.c"
  "$PORT_DIR/include/core_portme.h"
  "$PORT_DIR/minilibc.c"
  "$PORT_DIR/start.S"
  "$PORT_DIR/link.ld"
)
for file in "${required[@]}"; do
  [[ -f "$file" ]] || {
    echo "ERROR: missing $file; run perf/scripts/fetch_deps.sh first" >&2
    exit 1
  }
done
grep -q 'coremark_report' "$CORE_DIR/core_main.c" || {
  echo "ERROR: CoreMark reporting patch is not applied" >&2
  exit 1
}

mkdir -p "$BUILD_DIR"

COMMON_CFLAGS=(
  -O2
  -ffreestanding
  -fno-builtin
  -nostdlib
  -static
  -mcmodel=medany
  -msmall-data-limit=0
  -DITERATIONS="$ITERATIONS"
  -DTOTAL_DATA_SIZE="$TOTAL_DATA_SIZE"
  -I "$CORE_DIR"
  -I "$PORT_DIR/include"
)

CM_SRCS=(
  "$CORE_DIR/core_main.c"
  "$CORE_DIR/core_list_join.c"
  "$CORE_DIR/core_matrix.c"
  "$CORE_DIR/core_state.c"
  "$CORE_DIR/core_util.c"
  "$PORT_DIR/core_portme.c"
)

build_isa() {
  local isa=$1 march=$2 mabi=$3
  local elf="$BUILD_DIR/coremark-$isa.elf"
  local bin="$BUILD_DIR/coremark-$isa.bin"
  local hex="$BUILD_DIR/coremark-$isa.hex"

  "$RISCV_CC" -march="$march" -mabi="$mabi" \
    "${COMMON_CFLAGS[@]}" \
    -T "$PORT_DIR/link.ld" \
    "$PORT_DIR/start.S" "${CM_SRCS[@]}" "$PORT_DIR/minilibc.c" \
    -o "$elf"
  "$RISCV_OBJCOPY" -O binary "$elf" "$bin"
  od -An -v -tx8 "$bin" | tr -s ' ' '\n' | sed '/^$/d' > "$hex"
}

build_isa rv64 rv64im_zicsr lp64

HOST_BIN="$BUILD_DIR/coremark-host"
HOST_LOG="$BUILD_DIR/coremark-host.txt"
"$HOST_CC" -O2 -DCOREMARK_HOST \
  -DITERATIONS="$ITERATIONS" -DTOTAL_DATA_SIZE="$TOTAL_DATA_SIZE" \
  -I "$CORE_DIR" -I "$PORT_DIR/include" \
  "${CM_SRCS[@]}" -o "$HOST_BIN"
"$HOST_BIN" | tee "$HOST_LOG"

for expected in 'seedcrc=0xe9f5' 'crclist=0xe714' 'crcmatrix=0x1fd7' 'crcstate=0x8e3a' 'errors=0'; do
  grep -q "$expected" "$HOST_LOG" || {
    echo "ERROR: host CoreMark self-check failed (missing $expected)" >&2
    exit 1
  }
done

"$RISCV_SIZE" "$BUILD_DIR/coremark-rv64.elf"
{
  echo "ITERATIONS=$ITERATIONS"
  echo "TOTAL_DATA_SIZE=$TOTAL_DATA_SIZE"
  echo "RISCV_CC=$RISCV_CC"
  echo "CFLAGS=${COMMON_CFLAGS[*]}"
} > "$BUILD_DIR/build-info.txt"

echo "CoreMark host reference and RV64 image are ready in $BUILD_DIR"
