#!/usr/bin/env python3
"""Run two CPU models with one previously compiled app and validate the results."""
import argparse
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime

COMPARE_DIR = Path(__file__).resolve().parents[1]
ROOT = COMPARE_DIR.parent


def run_logged(command, path):
    print("Running: " + " ".join(map(str, command)), flush=True)
    with path.open("w") as log:
        process = subprocess.Popen(list(map(str, command)), stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True)
        try:
            for line in process.stdout:
                print(line, end="", flush=True)
                log.write(line)
            status = process.wait()
        except BaseException:
            process.terminate()
            process.wait()
            raise
    if status:
        raise RuntimeError(f"command exited with {status}; see {path}")


def one_record(path, prefix):
    lines = [line for line in path.read_text().splitlines() if line.startswith(prefix + " ")]
    if len(lines) != 1:
        raise ValueError(f"{path}: expected one {prefix} record, got {len(lines)}")
    return dict(item.split("=", 1) for item in lines[0].split()[1:] if "=" in item)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--clean-results", action="store_true")
    args = parser.parse_args()
    core = os.environ.get("NUTSHELL_CORE", "ooo")
    if core not in ("ooo", "inorder"):
        raise ValueError("NUTSHELL_CORE must be ooo or inorder")
    app = os.environ.get("APP", "coremark")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", app):
        raise ValueError("APP must be a directory name in apps/, e.g. APP=coremark")
    app_dir = ROOT / "apps" / app
    descriptor = app_dir / "compare.json"
    if not descriptor.is_file():
        raise ValueError(f"{app} has no comparison descriptor: {descriptor}")
    out = COMPARE_DIR / "results" / app / core
    if args.clean_results:
        shutil.rmtree(out, ignore_errors=True)
        print(f"Removed {out}")
        return
    spec = json.loads(descriptor.read_text())
    files = {key: (app_dir / spec[key]).resolve()
             for key in ("image", "elf", "hex", "reference", "validator") if key in spec}
    for key in ("image", "elf", "hex"):
        if key not in files:
            raise ValueError(f"{descriptor}: missing {key}")
    for path in files.values():
        if not path.is_file():
            raise ValueError(f"missing {path}; compile first: make -C {app_dir}")
    if "expected_checksum" not in spec and "validator" not in files:
        raise ValueError(f"{descriptor}: define expected_checksum or an app validator")
    image = files["image"].read_bytes()
    padded = image + b"\0" * (-len(image) % 8)
    hex_image = b"".join(int(word, 16).to_bytes(8, "little")
                         for word in files["hex"].read_text().split())
    if not image or len(image) > 16 * 1024 * 1024 or padded != hex_image:
        raise ValueError("app BIN/HEX disagree or exceed the 16 MiB DRAM; rebuild the app")
    if args.check:
        print(f"Selected app: {app} ({files['image']}), NUTSHELL_CORE={core}")
        return

    build = Path(os.environ.get("BUILD_DIR", COMPARE_DIR / "build")).resolve()
    binaries = {"nutshell": build / f"nutshell-{core}-emu", "mini": build / "mini-emu"}
    for binary in binaries.values():
        if not binary.is_file():
            raise ValueError(f"missing {binary}; run make build NUTSHELL_CORE={core} in {COMPARE_DIR}")
    limit = int(os.environ.get("MAX_CYCLES", "20000000"))
    window = int(os.environ.get("TRACE_WINDOW", "4096"))
    trace_option = os.environ.get("RETIRE_TRACE", "1")
    if limit <= 0 or window <= 0 or trace_option not in ("0", "1"):
        raise ValueError("MAX_CYCLES/TRACE_WINDOW must be positive; RETIRE_TRACE must be 0 or 1")
    trace = trace_option == "1"
    trace_args = []
    if trace:
        tool = os.environ.get("RISCV_NM", os.environ.get("CROSS_COMPILE", "riscv64-unknown-elf-") + "nm")
        symbols = {}
        for line in subprocess.check_output([tool, str(files["elf"])], text=True).splitlines():
            fields = line.split()
            if len(fields) == 3:
                symbols[fields[2]] = fields[0]
        for key in ("trace_start", "trace_stop"):
            if spec.get(key) not in symbols:
                raise ValueError(f"missing {key} symbol; define it in {descriptor} or set RETIRE_TRACE=0")
        trace_args = [f"+trace_start_pc=0x{symbols[spec['trace_start']]}",
                      f"+trace_stop_pc=0x{symbols[spec['trace_stop']]}"]
        os.environ["RISCV_NM"] = tool

    out.mkdir(parents=True, exist_ok=True)
    with (out / ".run.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        for name in ("summary.txt", "commit-summary.txt", "commit-windows.tsv"):
            (out / name).unlink(missing_ok=True)
        context = [f"Generated: {datetime.now().astimezone().isoformat()}", f"APP={app}",
                   f"NUTSHELL_CORE={core}",
                   f"Image: {files['image']}",
                   f"Image SHA256: {hashlib.sha256(image).hexdigest()}",
                   f"MAX_CYCLES={limit}", f"RETIRE_TRACE={trace_option}", f"TRACE_WINDOW={window}"]
        for path in (build / "dependencies.txt", app_dir / "build/build-info.txt"):
            context.append(path.read_text())
        for cpu, binary in binaries.items():
            context.append(f"{cpu} simulator: {binary}")
            context.append(f"{cpu} simulator SHA256: {hashlib.sha256(binary.read_bytes()).hexdigest()}")
        (out / "run-context.txt").write_text("\n".join(context) + "\n")
        reference_log = out / "reference.log"
        reference_log.unlink(missing_ok=True)
        for cpu in binaries:
            (out / f"{cpu}.log").unlink(missing_ok=True)
            (out / f"{cpu}.tsv").unlink(missing_ok=True)
        if "reference" in files:
            run_logged([files["reference"]], reference_log)
        results = {}
        for cpu, binary in binaries.items():
            log = out / f"{cpu}.log"
            command = [binary, f"+name={app}_{cpu}_{core}", f"+max_cycles={limit}",
                       f"+dram_init={files['hex']}"]
            if trace:
                command += [f"+trace={out / (cpu + '.tsv')}"] + trace_args
            run_logged(command, log)
            result = one_record(log, "RESULT")
            cycles, instret = int(result["cycles"]), int(result["instret"])
            if result["status"] != "ok" or min(cycles, instret) <= 0:
                raise ValueError(f"{cpu}: incomplete run or invalid performance counters")
            if int(result["signature"], 0) != 0x434D524B:
                raise ValueError(f"{cpu}: app reported a failure signature")
            if not math.isclose(float(result["ipc"]), instret / cycles, rel_tol=0, abs_tol=1e-6):
                raise ValueError(f"{cpu}: IPC disagrees with performance counters")
            if "expected_checksum" in spec and int(result["checksum"], 0) != spec["expected_checksum"]:
                raise ValueError(f"{cpu}: app checksum disagrees with the expected result")
            if "validator" in files:
                validator = [sys.executable, str(files["validator"]), "--log", str(log)]
                if "reference" in files:
                    validator += ["--reference", str(reference_log)]
                subprocess.run(validator, check=True)
            if trace and one_record(log, "TRACE")["status"] != "ok":
                raise ValueError(f"{cpu}: retirement trace is incomplete")
            results[cpu] = result
        nut, mini = results["nutshell"], results["mini"]
        if int(nut["checksum"], 0) != int(mini["checksum"], 0):
            raise ValueError("the two CPUs produced different app checksums")
        if trace:
            subprocess.run([sys.executable, str(COMPARE_DIR / "scripts/analyze_trace.py"),
                            "--nut", str(out / "nutshell.tsv"), "--mini", str(out / "mini.tsv"),
                            "--elf", str(files["elf"]), "--window", str(window),
                            "--out-windows", str(out / "commit-windows.tsv"),
                            "--out-summary", str(out / "commit-summary.txt")], check=True)
        label = "OOO" if core == "ooo" else "in-order"
        summary = [f"APP={app}: miniXiangShan vs NutShell {label} RV64",
                   f"NUTSHELL_CORE={core}", "Validation: passed"]
        for cpu, result in results.items():
            summary.append(cpu + " " + " ".join(f"{key}={result[key]}"
                           for key in ("cycles", "instret", "ipc", "signature", "checksum")))
        cycle_ratio = int(mini["cycles"]) / int(nut["cycles"])
        instret_ratio = int(mini["instret"]) / int(nut["instret"])
        summary.append(f"cycles_ratio={cycle_ratio:.6f} instret_ratio={instret_ratio:.6f} "
                       f"ipc_ratio={instret_ratio / cycle_ratio:.6f}")
        text = "\n".join(summary) + "\n"
        (out / "summary.txt").write_text(text)
        print(text, end="")
        print(f"Results: {out}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(f"ERROR: {error}")
