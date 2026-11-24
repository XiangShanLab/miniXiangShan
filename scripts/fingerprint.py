#!/usr/bin/env python3
"""Hash file names, contents and build options, including additions/deletions."""
import argparse
import hashlib
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--value", action="append", default=[])
parser.add_argument("paths", nargs="*")
args = parser.parse_args()
digest = hashlib.sha256()
for value in args.value:
    digest.update(value.encode() + b"\0")
for name in sorted(args.paths):
    root = Path(name)
    if not root.exists():
        parser.error(f"missing build input: {root}")
    files = sorted(p for p in root.rglob("*") if p.is_file()) if root.is_dir() else [root]
    for path in files:
        digest.update(str(path).encode() + b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
print(digest.hexdigest())
