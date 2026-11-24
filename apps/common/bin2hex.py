#!/usr/bin/env python3
"""Convert a raw RV64 image to little-endian 64-bit readmemh words."""
import sys
from pathlib import Path

source, target = map(Path, sys.argv[1:])
data = source.read_bytes()
data += b"\0" * (-len(data) % 8)
target.write_text("".join(f"{int.from_bytes(data[i:i+8], 'little'):016x}\n"
                          for i in range(0, len(data), 8)))
