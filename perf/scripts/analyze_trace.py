#!/usr/bin/env python3
"""Align and summarize ROB retirement-window traces from two RTL runs."""

from __future__ import annotations

import argparse
import bisect
import collections
import os
import shutil
import subprocess
import sys


def read_trace(path: str):
    """Return [(cycle, [(pc, inst), ...]), ...] in retirement order."""
    windows = []
    with open(path, "r", encoding="ascii") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line or line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if len(fields) < 2:
                raise ValueError(f"{path}:{line_number}: malformed trace row")
            cycle = int(fields[0])
            count = int(fields[1])
            if len(fields) != 2 + 2 * count:
                raise ValueError(f"{path}:{line_number}: expected {count} lanes")
            instructions = []
            for lane in range(count):
                pc = int(fields[2 + 2 * lane], 16)
                inst = int(fields[3 + 2 * lane], 16)
                instructions.append((pc, inst))
            if instructions:
                windows.append((cycle, instructions))
    if not windows:
        raise ValueError(f"{path}: trace is empty")
    return windows


def flatten(windows):
    instructions = []
    cycles = []
    for cycle, window in windows:
        for pc, inst in window:
            instructions.append((pc, inst))
            cycles.append(cycle)
    return instructions, cycles


def load_symbols(elf: str, nm_tool: str):
    result = subprocess.run(
        [nm_tool, "-n", "-S", "--defined-only", elf],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    symbols = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) < 3 or fields[-2] not in ("t", "T"):
            continue
        try:
            address = int(fields[0], 16)
        except ValueError:
            continue
        symbols.append((address, fields[-1]))
    symbols.sort()
    if not symbols:
        raise ValueError(f"{elf}: no text symbols found by {nm_tool}")
    return symbols


def symbolizer(symbols):
    addresses = [address for address, _ in symbols]

    def lookup(pc: int) -> str:
        index = bisect.bisect_right(addresses, pc) - 1
        if index < 0:
            return "unknown"
        return symbols[index][1]

    return lookup


def classify(inst: int) -> str:
    opcode = inst & 0x7F
    funct7 = (inst >> 25) & 0x7F
    if opcode == 0x03:
        return "load"
    if opcode == 0x23:
        return "store"
    if opcode in (0x63, 0x67, 0x6F):
        return "control"
    if opcode in (0x33, 0x3B) and funct7 == 0x01:
        return "muldiv"
    if opcode in (0x33, 0x3B, 0x13, 0x1B, 0x37, 0x17):
        return "alu"
    if opcode == 0x73:
        return "system"
    if opcode == 0x0F:
        return "fence"
    if opcode == 0x2F:
        return "atomic"
    return "other"


def destination_register(inst: int) -> int:
    """Return rd for instructions that can produce an integer register value."""
    opcode = inst & 0x7F
    if opcode in (0x03, 0x13, 0x1B, 0x33, 0x3B, 0x37, 0x17, 0x67, 0x6F):
        return (inst >> 7) & 0x1F
    return 0


def source_registers(inst: int) -> set[int]:
    """Return conservatively decoded integer source registers."""
    opcode = inst & 0x7F
    rs1 = (inst >> 15) & 0x1F
    rs2 = (inst >> 20) & 0x1F
    if opcode == 0x23 or opcode == 0x63 or opcode == 0x2F:
        return {rs1, rs2}
    if opcode in (0x03, 0x67, 0x6F, 0x73):
        return {rs1}
    if opcode in (0x13, 0x1B):
        return {rs1}
    if opcode in (0x33, 0x3B):
        return {rs1, rs2}
    return set()


def gap_bucket(gap: int) -> str:
    if gap <= 1:
        return "same_or_next"
    if gap == 2:
        return "2"
    if gap <= 4:
        return "3-4"
    if gap <= 8:
        return "5-8"
    if gap <= 16:
        return "9-16"
    if gap <= 32:
        return "17-32"
    if gap <= 64:
        return "33-64"
    return ">64"


GAP_BUCKETS = ("same_or_next", "2", "3-4", "5-8", "9-16", "17-32", "33-64", ">64")


def width_histograms(windows):
    histogram = collections.Counter()
    committed = 0
    for _, instructions in windows:
        histogram[len(instructions)] += 1
        committed += len(instructions)
    return histogram, committed


def gap_histogram(cycles):
    histogram = collections.Counter()
    for previous, current in zip(cycles, cycles[1:]):
        gap = current - previous - 1
        if gap == 0:
            bucket = "0"
        elif gap == 1:
            bucket = "1"
        elif gap <= 4:
            bucket = "2-4"
        elif gap <= 16:
            bucket = "5-16"
        elif gap <= 64:
            bucket = "17-64"
        elif gap <= 256:
            bucket = "65-256"
        else:
            bucket = ">256"
        histogram[bucket] += 1
    return histogram


def idle_gap_groups(windows, lookup):
    groups = collections.defaultdict(
        lambda: {"count": 0, "cycles": 0, "max": 0, "prev_pc": 0}
    )
    for (previous_cycle, previous_window), (current_cycle, current_window) in zip(
        windows, windows[1:]
    ):
        gap = current_cycle - previous_cycle - 1
        if gap <= 0:
            continue
        previous_pc = previous_window[-1][0]
        next_pc = current_window[0][0]
        key = (next_pc, lookup(next_pc))
        entry = groups[key]
        entry["count"] += 1
        entry["cycles"] += gap
        entry["max"] = max(entry["max"], gap)
        entry["prev_pc"] = previous_pc
    return sorted(
        groups.items(), key=lambda item: item[1]["cycles"], reverse=True
    )


def prefix_widths(windows, width: int):
    prefix = [0] * (len(windows) + 1)
    for index, (_, instructions) in enumerate(windows):
        prefix[index + 1] = prefix[index] + (1 if len(instructions) == width else 0)
    return prefix


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--nut", required=True)
    parser.add_argument("--mini", required=True)
    parser.add_argument("--elf", required=True)
    parser.add_argument("--window", type=int, default=4096)
    parser.add_argument("--out-windows", required=True)
    parser.add_argument("--out-summary", required=True)
    args = parser.parse_args()
    if args.window <= 0:
        parser.error("--window must be positive")

    nm_tool = os.environ.get("RISCV_NM", "riscv64-unknown-elf-nm")
    nm_path = shutil.which(nm_tool)
    if nm_path is None:
        raise FileNotFoundError(f"nm tool not found: {nm_tool}")

    nut_windows = read_trace(args.nut)
    mini_windows = read_trace(args.mini)
    nut_insts, nut_cycles = flatten(nut_windows)
    mini_insts, mini_cycles = flatten(mini_windows)
    symbols = load_symbols(args.elf, nm_path)
    lookup = symbolizer(symbols)

    mismatches = []
    common = min(len(nut_insts), len(mini_insts))
    for index in range(common):
        if nut_insts[index] != mini_insts[index]:
            mismatches.append((index, nut_insts[index], mini_insts[index]))
            if len(mismatches) == 10:
                break
    sequence_match = len(nut_insts) == len(mini_insts) and not mismatches
    if mismatches:
        common = mismatches[0][0]

    nut_cycles = nut_cycles[:common]
    mini_cycles = mini_cycles[:common]
    common_insts = nut_insts[:common]
    if common == 0:
        raise ValueError("the two traces have no common retirement instructions")

    nut_hist, nut_committed = width_histograms(nut_windows)
    mini_hist, mini_committed = width_histograms(mini_windows)
    nut_record_cycles = [cycle for cycle, _ in nut_windows]
    mini_record_cycles = [cycle for cycle, _ in mini_windows]
    nut_width_prefix = {w: prefix_widths(nut_windows, w) for w in (1, 2, 3)}
    mini_width_prefix = {w: prefix_widths(mini_windows, w) for w in (1, 2, 3)}

    rows = []
    window_delta_by_symbol = collections.defaultdict(int)
    window_count_by_symbol = collections.Counter()
    previous_nut_cycle = nut_cycles[0] - 1
    previous_mini_cycle = mini_cycles[0] - 1
    cumulative_delta = 0
    for window_id, start in enumerate(range(0, common, args.window)):
        end = min(start + args.window, common)
        nut_end_cycle = nut_cycles[end - 1]
        mini_end_cycle = mini_cycles[end - 1]
        nut_window_cycles = nut_end_cycle - previous_nut_cycle
        mini_window_cycles = mini_end_cycle - previous_mini_cycle
        delta = mini_window_cycles - nut_window_cycles

        nut_commits = (
            bisect.bisect_right(nut_record_cycles, nut_end_cycle)
            - bisect.bisect_right(nut_record_cycles, previous_nut_cycle)
        )
        mini_commits = (
            bisect.bisect_right(mini_record_cycles, mini_end_cycle)
            - bisect.bisect_right(mini_record_cycles, previous_mini_cycle)
        )

        nut_widths = {
            width: nut_width_prefix[width][
                bisect.bisect_right(nut_record_cycles, nut_end_cycle)
            ]
            - nut_width_prefix[width][
                bisect.bisect_right(nut_record_cycles, previous_nut_cycle)
            ]
            for width in (1, 2, 3)
        }
        mini_widths = {
            width: mini_width_prefix[width][
                bisect.bisect_right(mini_record_cycles, mini_end_cycle)
            ]
            - mini_width_prefix[width][
                bisect.bisect_right(mini_record_cycles, previous_mini_cycle)
            ]
            for width in (1, 2, 3)
        }

        symbol_counts = collections.Counter(
            lookup(pc) for pc, _ in common_insts[start:end]
        )
        dominant_symbol, dominant_count = symbol_counts.most_common(1)[0]
        categories = collections.Counter(
            classify(inst) for _, inst in common_insts[start:end]
        )
        window_delta_by_symbol[dominant_symbol] += delta
        window_count_by_symbol[dominant_symbol] += 1
        cumulative_delta += delta
        rows.append(
            {
                "window": window_id,
                "first_inst": start,
                "last_inst": end - 1,
                "nut_first_pc": common_insts[start][0],
                "nut_last_pc": common_insts[end - 1][0],
                "dominant_symbol": dominant_symbol,
                "dominant_share": dominant_count / (end - start),
                "nut_cycles": nut_window_cycles,
                "mini_cycles": mini_window_cycles,
                "delta_cycles": delta,
                "cumulative_delta_cycles": cumulative_delta,
                "nut_ipc": (end - start) / nut_window_cycles,
                "mini_ipc": (end - start) / mini_window_cycles,
                "nut_commit_cycles": nut_commits,
                "mini_commit_cycles": mini_commits,
                "nut_idle_cycles": nut_window_cycles - nut_commits,
                "mini_idle_cycles": mini_window_cycles - mini_commits,
                "nut_w1": nut_widths[1],
                "nut_w2": nut_widths[2],
                "mini_w1": mini_widths[1],
                "mini_w2": mini_widths[2],
                "mini_w3": mini_widths[3],
                "loads": categories["load"],
                "stores": categories["store"],
                "control": categories["control"],
                "muldiv": categories["muldiv"],
            }
        )
        previous_nut_cycle = nut_end_cycle
        previous_mini_cycle = mini_end_cycle

    current_class_stats = collections.defaultdict(
        lambda: {"count": 0, "delta": 0, "positive": 0, "negative": 0}
    )
    load_pc_stats = collections.defaultdict(lambda: {"count": 0, "nut": 0, "mini": 0})
    load_profile = {"NutShell": collections.Counter(), "miniXiangShan": collections.Counter()}
    load_profile_cycles = {
        "NutShell": collections.Counter(),
        "miniXiangShan": collections.Counter(),
    }
    load_counts = {"NutShell": 0, "miniXiangShan": 0}
    load_gaps = {"NutShell": 0, "miniXiangShan": 0}
    direct_load_use = collections.defaultdict(
        lambda: {"count": 0, "nut": 0, "mini": 0}
    )

    for index in range(1, common):
        nut_gap = nut_cycles[index] - nut_cycles[index - 1] - 1
        mini_gap = mini_cycles[index] - mini_cycles[index - 1] - 1
        delta = mini_gap - nut_gap
        previous_pc, previous_inst = common_insts[index - 1]
        current_pc, current_inst = common_insts[index]
        current_kind = classify(current_inst)

        entry = current_class_stats[current_kind]
        entry["count"] += 1
        entry["delta"] += delta
        entry["positive"] += max(delta, 0)
        entry["negative"] += min(delta, 0)
        if current_kind == "load":
            stats = load_pc_stats[current_pc]
            stats["count"] += 1
            stats["nut"] += nut_gap
            stats["mini"] += mini_gap
            nut_bucket = gap_bucket(nut_gap)
            mini_bucket = gap_bucket(mini_gap)
            load_profile["NutShell"][nut_bucket] += 1
            load_profile_cycles["NutShell"][nut_bucket] += nut_gap
            load_profile["miniXiangShan"][mini_bucket] += 1
            load_profile_cycles["miniXiangShan"][mini_bucket] += mini_gap
            load_counts["NutShell"] += 1
            load_counts["miniXiangShan"] += 1
            load_gaps["NutShell"] += nut_gap
            load_gaps["miniXiangShan"] += mini_gap

        if classify(previous_inst) == "load":
            destination = destination_register(previous_inst)
            if destination != 0 and destination in source_registers(current_inst):
                pair = (previous_pc, current_pc)
                stats = direct_load_use[pair]
                stats["count"] += 1
                stats["nut"] += nut_gap
                stats["mini"] += mini_gap

    top_load_pcs = sorted(
        load_pc_stats.items(), key=lambda item: item[1]["mini"] - item[1]["nut"], reverse=True
    )[:20]
    top_load_use = sorted(
        direct_load_use.items(),
        key=lambda item: item[1]["mini"] - item[1]["nut"],
        reverse=True,
    )[:20]

    region_size = 262144
    regions = []
    previous_nut_cycle = nut_cycles[0] - 1
    previous_mini_cycle = mini_cycles[0] - 1
    for start in range(0, common, region_size):
        end = min(start + region_size, common)
        nut_region_cycles = nut_cycles[end - 1] - previous_nut_cycle
        mini_region_cycles = mini_cycles[end - 1] - previous_mini_cycle
        midpoint = (start + end - 1) // 2
        symbol_counts = collections.Counter(
            lookup(common_insts[index][0]) for index in (start, midpoint, end - 1)
        )
        regions.append(
            {
                "first_inst": start,
                "last_inst": end - 1,
                "symbol": symbol_counts.most_common(1)[0][0],
                "nut_cycles": nut_region_cycles,
                "mini_cycles": mini_region_cycles,
                "delta_cycles": mini_region_cycles - nut_region_cycles,
            }
        )
        previous_nut_cycle = nut_cycles[end - 1]
        previous_mini_cycle = mini_cycles[end - 1]

    with open(args.out_windows, "w", encoding="ascii") as stream:
        columns = [
            "window",
            "first_inst",
            "last_inst",
            "nut_first_pc",
            "nut_last_pc",
            "dominant_symbol",
            "dominant_share",
            "nut_cycles",
            "mini_cycles",
            "delta_cycles",
            "cumulative_delta_cycles",
            "nut_ipc",
            "mini_ipc",
            "nut_commit_cycles",
            "mini_commit_cycles",
            "nut_idle_cycles",
            "mini_idle_cycles",
            "nut_w1",
            "nut_w2",
            "mini_w1",
            "mini_w2",
            "mini_w3",
            "loads",
            "stores",
            "control",
            "muldiv",
        ]
        stream.write("\t".join(columns) + "\n")
        for row in rows:
            values = []
            for column in columns:
                value = row[column]
                if column.endswith("_pc"):
                    values.append(f"0x{value:016x}")
                elif isinstance(value, float):
                    values.append(f"{value:.6f}")
                else:
                    values.append(str(value))
            stream.write("\t".join(values) + "\n")

    total_nut_cycles = nut_cycles[common - 1] - nut_cycles[0] + 1
    total_mini_cycles = mini_cycles[common - 1] - mini_cycles[0] + 1
    total_delta = total_mini_cycles - total_nut_cycles
    top_windows = sorted(rows, key=lambda row: row["delta_cycles"], reverse=True)[:20]
    top_symbols = sorted(
        window_delta_by_symbol.items(), key=lambda item: item[1], reverse=True
    )[:20]
    nut_idle_groups = idle_gap_groups(nut_windows, lookup)
    mini_idle_groups = idle_gap_groups(mini_windows, lookup)

    with open(args.out_summary, "w", encoding="ascii") as stream:
        def emit(text=""):
            stream.write(text + "\n")

        emit("RETIRE_TRACE")
        emit(f"sequence_match={'yes' if sequence_match else 'no'}")
        emit(f"common_instructions={common}")
        emit(f"nut_instructions={len(nut_insts)}")
        emit(f"mini_instructions={len(mini_insts)}")
        emit(f"window_instructions={args.window}")
        emit(f"nut_retire_windows={len(nut_windows)}")
        emit(f"mini_retire_windows={len(mini_windows)}")
        emit(f"nut_first_cycle={nut_cycles[0]} nut_last_cycle={nut_cycles[-1]}")
        emit(f"mini_first_cycle={mini_cycles[0]} mini_last_cycle={mini_cycles[-1]}")
        emit(f"nut_trace_cycles={total_nut_cycles}")
        emit(f"mini_trace_cycles={total_mini_cycles}")
        emit(f"trace_delta_cycles={total_delta}")
        emit(f"trace_cycles_ratio={total_mini_cycles / total_nut_cycles:.6f}")
        emit(f"nut_trace_ipc={common / total_nut_cycles:.6f}")
        emit(f"mini_trace_ipc={common / total_mini_cycles:.6f}")
        emit()
        emit("RETIRE_ACTIVITY")
        emit(
            "core\ttotal_cycles\tactive_commit_cycles\tidle_cycles\t"
            "commit_density\tavg_retired_per_active_cycle"
        )
        for core, total, active, retired in (
            ("NutShell", total_nut_cycles, len(nut_record_cycles), nut_committed),
            (
                "miniXiangShan",
                total_mini_cycles,
                len(mini_record_cycles),
                mini_committed,
            ),
        ):
            emit(
                f"{core}\t{total}\t{active}\t{total - active}\t"
                f"{active / total:.6f}\t{retired / active:.6f}"
            )
        emit()
        emit("GAP_DELTA_BY_CURRENT_CLASS")
        emit("current_class\tinstructions\tdelta_cycles\tpositive_cycles\tnegative_cycles")
        for kind, entry in sorted(
            current_class_stats.items(), key=lambda item: item[1]["delta"], reverse=True
        ):
            emit(
                f"{kind}\t{entry['count']}\t{entry['delta']}\t"
                f"{entry['positive']}\t{entry['negative']}"
            )
        emit()
        emit("LOAD_GAP_PROFILE")
        emit(
            "core\tloads\ttotal_gap_cycles\tavg_gap\t"
            + "\t".join(GAP_BUCKETS)
        )
        for core in ("NutShell", "miniXiangShan"):
            emit(
                f"{core}\t{load_counts[core]}\t{load_gaps[core]}\t"
                f"{load_gaps[core] / load_counts[core]:.6f}\t"
                + "\t".join(
                    str(load_profile[core][bucket]) for bucket in GAP_BUCKETS
                )
            )
        emit(
            "bucket_cycles: core\t"
            + "\t".join(GAP_BUCKETS)
        )
        for core in ("NutShell", "miniXiangShan"):
            emit(
                f"{core}\t"
                + "\t".join(
                    str(load_profile_cycles[core][bucket]) for bucket in GAP_BUCKETS
                )
            )
        emit()
        emit("TOP_LOAD_PCS_BY_GAP_DELTA")
        emit(
            "pc\tsymbol\tloads\tnut_gap_cycles\tmini_gap_cycles\tdelta_cycles\t"
            "nut_avg_gap\tmini_avg_gap"
        )
        for pc, entry in top_load_pcs:
            count = entry["count"]
            emit(
                f"0x{pc:016x}\t{lookup(pc)}\t{count}\t{entry['nut']}\t"
                f"{entry['mini']}\t{entry['mini'] - entry['nut']}\t"
                f"{entry['nut'] / count:.6f}\t{entry['mini'] / count:.6f}"
            )
        emit()
        direct_count = sum(entry["count"] for entry in direct_load_use.values())
        direct_nut = sum(entry["nut"] for entry in direct_load_use.values())
        direct_mini = sum(entry["mini"] for entry in direct_load_use.values())
        emit("DIRECT_LOAD_USE")
        emit(
            f"pairs={direct_count} nut_gap_cycles={direct_nut} "
            f"mini_gap_cycles={direct_mini} delta_cycles={direct_mini - direct_nut}"
        )
        emit("previous_pc\tnext_pc\tsymbol\tpairs\tnut_gap_cycles\tmini_gap_cycles\tdelta_cycles")
        for (previous_pc, next_pc), entry in top_load_use:
            emit(
                f"0x{previous_pc:016x}\t0x{next_pc:016x}\t{lookup(next_pc)}\t"
                f"{entry['count']}\t{entry['nut']}\t{entry['mini']}\t"
                f"{entry['mini'] - entry['nut']}"
            )
        emit()
        emit("COMMIT_WIDTH_COUNTS")
        emit("core\twidth1_windows\twidth2_windows\twidth3_windows\tretired")
        emit(
            f"NutShell\t{nut_hist[1]}\t{nut_hist[2]}\t{nut_hist[3]}\t{nut_committed}"
        )
        emit(
            f"miniXiangShan\t{mini_hist[1]}\t{mini_hist[2]}\t{mini_hist[3]}\t{mini_committed}"
        )
        emit()
        emit("COMMIT_GAPS")
        nut_gaps = gap_histogram(nut_record_cycles)
        mini_gaps = gap_histogram(mini_record_cycles)
        emit("core\tgap_0\tgap_1\tgap_2_4\tgap_5_16\tgap_17_64\tgap_65_256\tgap_gt_256")
        emit(
            "NutShell\t"
            + "\t".join(
                str(nut_gaps[key])
                for key in ("0", "1", "2-4", "5-16", "17-64", "65-256", ">256")
            )
        )
        emit(
            "miniXiangShan\t"
            + "\t".join(
                str(mini_gaps[key])
                for key in ("0", "1", "2-4", "5-16", "17-64", "65-256", ">256")
            )
        )
        emit()
        emit("TOP_WINDOWS_BY_DELTA")
        emit(
            "rank\twindow\tfirst_inst\tlast_inst\tfirst_pc\tlast_pc\tsymbol\t"
            "nut_cycles\tmini_cycles\tdelta_cycles\tnut_ipc\tmini_ipc\t"
            "nut_idle\tmini_idle\tnut_w1\tnut_w2\tmini_w1\tmini_w2\tmini_w3"
        )
        for rank, row in enumerate(top_windows, 1):
            emit(
                f"{rank}\t{row['window']}\t{row['first_inst']}\t{row['last_inst']}\t"
                f"0x{row['nut_first_pc']:016x}\t0x{row['nut_last_pc']:016x}\t"
                f"{row['dominant_symbol']}\t{row['nut_cycles']}\t{row['mini_cycles']}\t"
                f"{row['delta_cycles']}\t{row['nut_ipc']:.6f}\t{row['mini_ipc']:.6f}\t"
                f"{row['nut_idle_cycles']}\t{row['mini_idle_cycles']}\t"
                f"{row['nut_w1']}\t{row['nut_w2']}\t{row['mini_w1']}\t"
                f"{row['mini_w2']}\t{row['mini_w3']}"
            )
        emit()
        emit("WINDOW_DELTA_BY_DOMINANT_SYMBOL")
        emit("symbol\twindows\tdelta_cycles")
        for symbol, delta in top_symbols:
            emit(f"{symbol}\t{window_count_by_symbol[symbol]}\t{delta}")
        emit()
        emit("GAP_DELTA_BY_REGION")
        emit("first_inst\tlast_inst\tsymbol\tnut_cycles\tmini_cycles\tdelta_cycles")
        for region in regions:
            emit(
                f"{region['first_inst']}\t{region['last_inst']}\t{region['symbol']}\t"
                f"{region['nut_cycles']}\t{region['mini_cycles']}\t"
                f"{region['delta_cycles']}"
            )
        for label, groups in (
            ("TOP_IDLE_GAPS_NUTSHELL", nut_idle_groups),
            ("TOP_IDLE_GAPS_MINIXIANGSHAN", mini_idle_groups),
        ):
            emit()
            emit(label)
            emit(
                "next_pc\tsymbol\tgaps\ttotal_idle_cycles\tmax_gap\tprevious_pc"
            )
            for (next_pc, symbol), entry in groups[:30]:
                emit(
                    f"0x{next_pc:016x}\t{symbol}\t{entry['count']}\t"
                    f"{entry['cycles']}\t{entry['max']}\t"
                    f"0x{entry['prev_pc']:016x}"
                )
        if mismatches:
            emit()
            emit("FIRST_SEQUENCE_MISMATCHES")
            emit("index\tnut_pc\tnut_inst\tmini_pc\tmini_inst")
            for index, nut, mini in mismatches:
                emit(
                    f"{index}\t0x{nut[0]:016x}\t0x{nut[1]:08x}\t"
                    f"0x{mini[0]:016x}\t0x{mini[1]:08x}"
                )

    with open(args.out_summary, "r", encoding="ascii") as stream:
        sys.stdout.write(stream.read())

    return 0 if sequence_match else 3


if __name__ == "__main__":
    raise SystemExit(main())
