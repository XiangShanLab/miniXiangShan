#!/usr/bin/env python3
"""Check one CoreMark RTL run against this app's host reference."""
import argparse
import re
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--reference", required=True)
parser.add_argument("--log", required=True)
args = parser.parse_args()
host_lines = [line for line in Path(args.reference).read_text().splitlines()
              if line.startswith("COREMARK_HOST ")]
if len(host_lines) != 1:
    parser.exit(1, "ERROR: expected one COREMARK_HOST record\n")
host = dict(token.split("=", 1) for token in host_lines[0].split()[1:])
if host.get("errors") != "0":
    parser.exit(1, "ERROR: CoreMark host reference failed its self-check\n")
text = Path(args.log).read_text()
for key in ("seedcrc", "crclist", "crcmatrix", "crcstate", "crcfinal"):
    label = key if key == "seedcrc" else "[0]" + key
    values = re.findall(r"^" + re.escape(label) + r"\s*:\s*(0x[0-9a-fA-F]+)\s*$", text, re.M)
    if len(values) != 1 or int(values[0], 16) != int(host[key], 0):
        parser.exit(1, f"ERROR: CoreMark {key} differs from the host reference\n")
result_lines = [line for line in text.splitlines() if line.startswith("RESULT ")]
if len(result_lines) != 1:
    parser.exit(1, "ERROR: expected one RESULT record\n")
result = dict(token.split("=", 1) for token in result_lines[0].split()[1:])
if int(result["checksum"], 0) != int(host["crcfinal"], 0):
    parser.exit(1, "ERROR: CoreMark MMIO checksum differs from the host reference\n")
