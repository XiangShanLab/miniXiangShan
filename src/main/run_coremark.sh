#!/usr/bin/env bash
set -euo pipefail

MAIN_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$MAIN_DIR/../.." && pwd)
SCRIPTS="$ROOT/perf/scripts"
export COREMARK_DIR=${COREMARK_DIR:-"${DEPS_DIR:-"$ROOT/.deps"}/coremark"}
export PERF_BUILD_DIR=${PERF_BUILD_DIR:-"$ROOT/perf/.build"}
export COREMARK_BUILD_DIR="$PERF_BUILD_DIR/coremark"
OUT_DIR=${OUT_DIR:-"$ROOT/perf/results/mini"}
MAX_CYCLES=${MAX_CYCLES:-20000000}

if [[ $# != 0 ]]; then
  echo "Usage: bash $0" >&2
  echo "Optional environment: ITERATIONS, MAX_CYCLES, OUT_DIR, SBT_BIN, DEPS_DIR" >&2
  exit 2
fi

echo "[1/4] Preparing CoreMark sources"
bash "$SCRIPTS/fetch_deps.sh" --coremark-only
echo "[2/4] Building CoreMark and host reference"
bash "$SCRIPTS/build_coremark.sh"
echo "[3/4] Building miniXiangShan"
bash "$SCRIPTS/build_mini.sh"
echo "[4/4] Running and validating miniXiangShan CoreMark"
mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/coremark-mini.log"
SUMMARY="$OUT_DIR/summary.txt"
rm -f "$SUMMARY"
"$PERF_BUILD_DIR/obj-mini/miniperf-rv64" \
  +name=coremark_mini_rv64 +max_cycles="$MAX_CYCLES" \
  +dram_init="$COREMARK_BUILD_DIR/coremark-rv64.hex" | tee "$LOG"

python3 - "$COREMARK_BUILD_DIR/coremark-host.txt" "$LOG" "$SUMMARY" <<'PY'
import math
import re
import sys
from pathlib import Path

host_path, log_path, summary_path = map(Path, sys.argv[1:])
host_text = host_path.read_text()
text = log_path.read_text()


def fields(source, prefix):
    lines = [line for line in source.splitlines() if line.startswith(prefix)]
    if len(lines) != 1:
        raise ValueError(f"expected one {prefix.strip()} record, got {len(lines)}")
    return dict(token.split("=", 1) for token in lines[0].split()[1:] if "=" in token)


try:
    host = fields(host_text, "COREMARK_HOST ")
    result = fields(text, "RESULT ")
    if host["errors"] != "0" or result["status"] != "ok":
        raise ValueError("host self-check failed or RTL simulation did not complete")
    if int(result["signature"], 0) != 0x434D524B:
        raise ValueError("invalid completion signature")
    for key in ("seedcrc", "crclist", "crcmatrix", "crcstate", "crcfinal"):
        label = key if key == "seedcrc" else "[0]" + key
        values = re.findall(r"^" + re.escape(label) + r"\s*:\s*(0x[0-9a-fA-F]+)\s*$", text, re.M)
        if len(values) != 1 or int(values[0], 16) != int(host[key], 0):
            raise ValueError(f"{key} does not match host reference")
    if int(result["checksum"], 0) != int(host["crcfinal"], 0):
        raise ValueError("MMIO checksum does not match host reference")
    iterations = int(host["iterations"])
    cycles, instret = int(result["cycles"]), int(result["instret"])
    if min(iterations, cycles, instret) <= 0:
        raise ValueError("invalid iteration count or performance counters")
    if not math.isclose(float(result["ipc"]), instret / cycles, abs_tol=1e-6):
        raise ValueError("IPC does not match counters")
    summary = (
        "miniXiangShan RV64 CoreMark\n"
        f"Validation: CRCs match host, errors=0, signature={result['signature']}\n"
        f"iterations={iterations} cycles={cycles} instret={instret} ipc={result['ipc']}\n"
        f"cycles_per_iteration={cycles / iterations:.2f}\n"
    )
except (KeyError, ValueError) as error:
    sys.exit(f"ERROR: CoreMark validation failed: {error}")

summary_path.write_text(summary)
print(summary, end="")
PY
echo "Results: $OUT_DIR"
