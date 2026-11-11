#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PERF_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
BUILD_DIR=${PERF_BUILD_DIR:-"$PERF_DIR/.build"}
OUT_DIR=${OUT_DIR:-"$PERF_DIR/results"}

NUT_BIN=${NUT_BIN:-"$BUILD_DIR/obj-nutshell/nutshell-perf"}
MINI_BIN=${MINI_BIN:-"$BUILD_DIR/obj-mini/miniperf-rv64"}
NUT_HEX=${NUT_HEX:-"$BUILD_DIR/coremark/coremark-rv64.hex"}
MINI_HEX=${MINI_HEX:-"$BUILD_DIR/coremark/coremark-rv64.hex"}
HOST_LOG=${HOST_LOG:-"$BUILD_DIR/coremark/coremark-host.txt"}
MAX_CYCLES=${MAX_CYCLES:-20000000}
TRACE=${TRACE:-1}
TRACE_WINDOW=${TRACE_WINDOW:-4096}
RISCV_NM=${RISCV_NM:-riscv64-unknown-elf-nm}
ELF=${ELF:-"$BUILD_DIR/coremark/coremark-rv64.elf"}

NUT_LOG="$OUT_DIR/coremark-nutshell.log"
MINI_LOG="$OUT_DIR/coremark-mini.log"
SUMMARY="$OUT_DIR/summary.txt"
CONTEXT="$OUT_DIR/run-context.txt"
TRACE_DIR="$BUILD_DIR/traces"
NUT_TRACE="$TRACE_DIR/coremark-nutshell.tsv"
MINI_TRACE="$TRACE_DIR/coremark-mini.tsv"
WINDOWS_REPORT="$OUT_DIR/commit-windows.tsv"
TRACE_SUMMARY="$OUT_DIR/commit-summary.txt"

for file in "$NUT_BIN" "$MINI_BIN" "$NUT_HEX" "$MINI_HEX" "$HOST_LOG"; do
  [[ -e "$file" ]] || {
    echo "ERROR: missing $file; run 'make perf' so the harness can build it" >&2
    exit 1
  }
done

mkdir -p "$OUT_DIR"

result_field() {
  local file=$1 key=$2
  awk -v key="$key" '/^RESULT/ {
    for (i = 1; i <= NF; ++i) {
      split($i, pair, "=")
      if (pair[1] == key) print pair[2]
    }
  }' "$file"
}

host_field() {
  local key=$1
  awk -v key="$key" '/^COREMARK_HOST/ {
    for (i = 1; i <= NF; ++i) {
      split($i, pair, "=")
      if (pair[1] == key) print pair[2]
    }
  }' "$HOST_LOG"
}

console_field() {
  local file=$1 key=$2
  awk -v key="$key" '$1 == key {print $3}' "$file" | tail -1
}

normalize_hex() {
  printf '0x%08x' "$(( $1 ))"
}

run_simulator() {
  local label=$1 binary=$2 name=$3 hex=$4 log=$5 trace=$6
  echo "===== $label ====="
  echo "binary=$binary"
  echo "image=$hex"
  local trace_args=()
  if [[ "$TRACE" == 1 ]]; then
    trace_args=(+trace="$trace" +trace_start_pc="$TRACE_START_PC"
                +trace_stop_pc="$TRACE_STOP_PC")
    echo "trace=$trace"
  fi
  "$binary" +name="$name" +max_cycles="$MAX_CYCLES" +dram_init="$hex" \
    "${trace_args[@]}" | tee "$log"
  echo
}

TRACE_START_PC=
TRACE_STOP_PC=
if [[ "$TRACE" == 1 ]]; then
  command -v "$RISCV_NM" >/dev/null 2>&1 || {
    echo "ERROR: trace analysis requires $RISCV_NM" >&2
    exit 1
  }
  [[ -f "$ELF" ]] || {
    echo "ERROR: missing $ELF; build CoreMark first" >&2
    exit 1
  }
  TRACE_START_PC=$("$RISCV_NM" "$ELF" | awk '$3 == "iterate" {print "0x" $1; exit}')
  TRACE_STOP_PC=$("$RISCV_NM" "$ELF" | awk '$3 == "stop_time" {print "0x" $1; exit}')
  [[ -n "$TRACE_START_PC" && -n "$TRACE_STOP_PC" ]] || {
    echo "ERROR: cannot resolve iterate/stop_time symbols in $ELF" >&2
    exit 1
  }
  mkdir -p "$TRACE_DIR"
fi

{
  echo "miniXiangShan performance comparison"
  echo "Generated: $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "Host: $(hostname)"
  cat "$BUILD_DIR/dependencies.txt"
  echo
  cat "$BUILD_DIR/coremark/build-info.txt"
  echo "MAX_CYCLES=$MAX_CYCLES"
  echo "TRACE=$TRACE"
  echo "TRACE_WINDOW=$TRACE_WINDOW"
  echo "TRACE_START_PC=$TRACE_START_PC"
  echo "TRACE_STOP_PC=$TRACE_STOP_PC"
} > "$CONTEXT"

run_simulator "NutShell RV64" "$NUT_BIN" coremark_nutshell_rv64 "$NUT_HEX" "$NUT_LOG" "$NUT_TRACE"
run_simulator "miniXiangShan RV64" "$MINI_BIN" coremark_mini_rv64 "$MINI_HEX" "$MINI_LOG" "$MINI_TRACE"

for log in "$NUT_LOG" "$MINI_LOG"; do
  [[ $(result_field "$log" status) == ok ]] || {
    echo "ERROR: simulation timed out or did not reach the done register: $log" >&2
    exit 1
  }
done

expected_signature=0x434d524b
errors=$(host_field errors)
expected_crc=$(host_field crcfinal)
expected_iterations=$(host_field iterations)

[[ "$errors" == 0 ]] || {
  echo "ERROR: host CoreMark self-check failed" >&2
  exit 1
}

for key in seedcrc crclist crcmatrix crcstate crcfinal; do
  expected=$(host_field "$key")
  if [[ "$key" == seedcrc ]]; then
    console_key=seedcrc
  else
    console_key="[0]$key"
  fi
  for pair in "NutShell:$NUT_LOG" "miniXiangShan:$MINI_LOG"; do
    label=${pair%%:*}
    log=${pair#*:}
    actual=$(console_field "$log" "$console_key")
    [[ $(normalize_hex "$actual") == $(normalize_hex "$expected") ]] || {
      echo "ERROR: $label $key mismatch (expected $expected, got $actual)" >&2
      exit 1
    }
  done
done

for pair in "NutShell:$NUT_LOG" "miniXiangShan:$MINI_LOG"; do
  label=${pair%%:*}
  log=${pair#*:}
  signature=$(result_field "$log" signature)
  checksum=$(result_field "$log" checksum)
  [[ $(normalize_hex "$signature") == $(normalize_hex "$expected_signature") ]] || {
    echo "ERROR: $label CoreMark signature mismatch ($signature)" >&2
    exit 1
  }
  [[ $(normalize_hex "$checksum") == $(normalize_hex "$expected_crc") ]] || {
    echo "ERROR: $label crcfinal mismatch in MMIO ($checksum)" >&2
    exit 1
  }
done

nut_cycles=$(result_field "$NUT_LOG" cycles)
mini_cycles=$(result_field "$MINI_LOG" cycles)
nut_instret=$(result_field "$NUT_LOG" instret)
mini_instret=$(result_field "$MINI_LOG" instret)
nut_ipc=$(result_field "$NUT_LOG" ipc)
mini_ipc=$(result_field "$MINI_LOG" ipc)
nut_sig=$(result_field "$NUT_LOG" signature)
mini_sig=$(result_field "$MINI_LOG" signature)

{
  echo "CoreMark comparison: NutShell OOO RV64 vs miniXiangShan OOO RV64"
  echo "Benchmark: EEMBC CoreMark 1.0, 2K performance configuration"
  echo "Parameters: TOTAL_DATA_SIZE=2000, ITERATIONS=$expected_iterations"
  echo "Counter scope: CoreMark start_time() through stop_time()"
  echo "Validation: seedcrc=$(host_field seedcrc) crclist=$(host_field crclist) crcmatrix=$(host_field crcmatrix) crcstate=$(host_field crcstate) crcfinal=$(host_field crcfinal) errors=$errors"
  echo
  echo "NUT  cycles=$nut_cycles instret=$nut_instret ipc=$nut_ipc signature=$nut_sig crcfinal=0x$(printf '%08x' "$(( $expected_crc ))")"
  echo "MINI cycles=$mini_cycles instret=$mini_instret ipc=$mini_ipc signature=$mini_sig crcfinal=0x$(printf '%08x' "$(( $expected_crc ))")"
  echo
  awk -v nc="$nut_cycles" -v mc="$mini_cycles" \
      -v ni="$nut_instret" -v mi="$mini_instret" \
      -v nipc="$nut_ipc" -v mipc="$mini_ipc" \
      -v iter="$expected_iterations" 'BEGIN {
        printf "cycles_ratio=%.6f instret_ratio=%.6f ipc_ratio=%.6f\n", mc/nc, mi/ni, mipc/nipc
        printf "cycles_per_iteration: NutShell=%.2f mini=%.2f\n", nc/iter, mc/iter
      }'
} | tee "$SUMMARY"

if [[ "$TRACE" == 1 ]]; then
  echo
  echo "===== Commit-window analysis ====="
  python3 "$SCRIPT_DIR/analyze_trace.py" \
    --nut "$NUT_TRACE" \
    --mini "$MINI_TRACE" \
    --elf "$ELF" \
    --window "$TRACE_WINDOW" \
    --out-windows "$WINDOWS_REPORT" \
    --out-summary "$TRACE_SUMMARY"
fi

echo
echo "Logs and summary written to $OUT_DIR"
