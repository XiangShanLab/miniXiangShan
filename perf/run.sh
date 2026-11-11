#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SCRIPTS="$SCRIPT_DIR/scripts"

echo "[1/5] Preparing external NutShell and CoreMark sources"
"$SCRIPTS/fetch_deps.sh"
echo "[2/5] Building CoreMark host reference and bare-metal images"
"$SCRIPTS/build_coremark.sh"
echo "[3/5] Building miniXiangShan RTL and Verilator model"
"$SCRIPTS/build_mini.sh"
echo "[4/5] Building NutShell RTL and Verilator model"
"$SCRIPTS/build_nutshell.sh"
echo "[5/5] Running, validating, and comparing CoreMark"
"$SCRIPTS/run_compare.sh"
