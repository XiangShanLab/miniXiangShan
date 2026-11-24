# miniXiangShan emu

The emulator follows the XiangShan workflow: Verilator runs the RTL and a
NEMU shared library advances in architectural retirement order. This is a
mini-specific harness, not the full upstream XiangShan Difftest framework.
It does not require another local XiangShan, NutShell, or NEMU checkout.

## Build

```bash
make emu
build/emu --help
```

Requirements: JDK/sbt, Verilator with C++17, a C/C++ compiler, make, Git, Python 3, flock,
flex, bison, readline/zlib/zstd development libraries, and the dependencies
required by the pinned NEMU version.

`difftest/deps.lock` pins the public NEMU URL and commit. `make emu` fetches
it into `.deps/nemu-ref`, builds an integer-only RV64 reference and installs
the library in `build/ref/`.
The build rejects a different commit, modified source, or a local-path origin.
NEMU's own dependency fetching runs inside that independent checkout.
No repository-external local source files are imported.

Configure BPU, Load Queue and L2 prefetch in `CoreConfigKeys` in
`src/main/scala/config/Core.scala`, then rebuild.

Build variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `WITH_DIFFTEST` | `1` | Compile NEMU support and build the reference; `0` builds a standalone simulator |
| `WITH_WAVE` | `1` | Compile FST waveform support |
| `JOBS` | `8` | Parallel C/C++ build jobs |
| `SBT` | auto | sbt executable |
| `BUILD_DIR` | `build` | CPU build root; RTL is in `rtl/emu/`, NEMU library in `ref/` |
| `DEPS_DIR` | `.deps` | External source checkout cache |

For example:

```bash
make emu WITH_DIFFTEST=0 WITH_WAVE=0
build/emu -i program.bin
make emu WITH_DIFFTEST=1 WITH_WAVE=1
```

Compile-time options need a rebuild. Runtime `--no-diff` does not require
a rebuild and never loads NEMU. Both builds retain the retirement monitor
for program termination, tracing, and instruction limits; neither emu build
instantiates the old unresolved DPI blackboxes.

## Run

```bash
build/emu -i program.bin
build/emu -i program.bin --no-diff
build/emu -i program.bin --diff build/ref/riscv64-nemu-interpreter-so
build/emu -i program.bin --max-cycles 1000000 --memory-latency 7 --seed 42
build/emu -i program.bin --wave build/run.fst --trace build/retire.log
build/emu --version
```

Workloads are compiled independently, for example `make -C apps/hello` or
`make -C apps/coremark ITERATIONS=10`. Then invoke `build/emu` manually with
`-i apps/<name>/build/<name>.bin`. CPU builds print the commands and do not
compile or execute an application. See [apps/README.md](../apps/README.md).

Images must be raw little-endian RV64 binaries, linked at `0x80000000`.
The default DRAM is 16 MiB, zero-initialized; `--ram-size` changes it for
both DUT and reference. ELF files must first be converted with
`riscv64-unknown-elf-objcopy -O binary`. Hex files used by the performance
platform are not binary images.

The default reference path is resolved relative to `build/emu`, so running
the emulator from another working directory is supported. `--diff` can
override it, but its ABI must match the pinned integer-only configuration.

`ebreak` ends a test by default: `a0=0` is a good trap, nonzero is a bad trap.
Use `--trap none` to exercise actual breakpoint exceptions. The existing
performance MMIO convention is also supported: write `0x434d524b` to
`0x4000f000`, then write to `0x4000f030`. Console writes use `0x4000f038`.
Successful retired loads/stores must fall entirely within DRAM or this MMIO
window, including loads whose destination is `x0`. An out-of-range retirement
fails before NEMU execution or MMIO synchronization, even with `--no-diff`.
Writes outside the map still fail at the AXI interface. Addresses never wrap
around into DRAM.

AXI reads may come from instructions that will later be squashed, or from
prefetches. By default, unmapped read beats complete with zero placeholder
data so outstanding bursts can drain and branch redirects can finish. These
reads produce no notices, counters, or extra result fields. No instruction is
skipped in NEMU because of these reads; PC/GPR/CSR/store checks remain active.
Add `--strict-axi-reads`
to restore immediate failure at the first unmapped AXI read response.

This is a simulator policy to tolerate speculative reads, not a hardware
access-fault implementation. The AXI interface has no instruction/ROB identity,
so an unmapped bus request alone does not prove that an instruction was
squashed. Untranslated instruction PCs are also checked against DRAM at
retirement; the commit ABI has no physical fetch address for Sv39 checking.
The current L2 does not propagate AXI `RRESP` errors to architectural traps.
Implementing precise access faults requires that hardware error path as well.

Exit codes: `0` good trap, MMIO completion, or requested instruction limit;
`1` mismatch, bad trap, invalid argument/image/reference, or AXI error;
`2` timeout or lack of retirement progress; `130` interrupted.
Instruction limits stop at retirement-group boundaries.
Successful exits print `EMU_RESULT status=success`; the `reason` field records
`good-trap`, `mmio-exit`, or `instruction-limit`. Speculative unmapped reads do
not change a successful result. Actual committed access errors, difftest
mismatches, and timeouts retain their failure statuses and exit codes.

## Failure Diagnostics

On a difftest failure, emu writes a sectioned ASCII report to stderr:

- A stable error code, the failed condition, cycle/retirement counts, check
  phase, active lane, image/reference paths, and runtime configuration.
- The checked DUT/NEMU values and their bitwise XOR, followed by inspection
  suggestions. Group-state failures list all differing checked GPR/CSR fields.
- The current retirement group, including PC, instruction, GPR writeback,
  load/store addresses and store data, plus exception/interrupt/xRET metadata.
- All 32 GPRs side by side with their ABI names, and all privilege/CSR fields
  exposed by the reference ABI. `!=` marks different snapshot values.
- The last 32 processed retirement events, oldest first, and trace/wave paths
  or the options needed to enable them.

The same state/history report is also printed before timeout exits:

- `SIMULATION STALLED` / `NO_COMMIT_TIMEOUT`: no valid retirement event for
  `--stuck-limit N` cycles (default `100000`). The report shows the elapsed
  no-commit cycles, last retirement cycle/PC/instruction, and configuration.
  If there has been no retirement since reset, that is stated explicitly.
- `SIMULATION TIMEOUT` / `MAX_CYCLES`: the total `--max-cycles N` limit was
  reached (default `20000000`). The report suggests checking loop termination
  and the EBREAK/MMIO completion path, or extending the limit for long workloads.
- `SIMULATION STOPPED` / `RTL_FINISH`: RTL requested `$finish` before the
  application reported completion.

Runtime reports include the AXI AR/R/AW/W/B valid/ready levels and request
addresses to help inspect outstanding memory traffic. Timeout exits retain
exit code `2` and the final `EMU_RESULT status=stuck` or `status=timeout` line.
Reports are written to stderr even with `--no-diff`; DUT state is shown and
NEMU values are marked unavailable when no reference is loaded.

The report distinguishes an instruction's writeback check from the complete
architectural register snapshot. DUT register ports describe the whole
retirement group; NEMU stops at the event that failed. If a failure occurs
before the group has finished, later DUT updates can appear as additional
snapshot differences. The **DETECTED DIFFERENCES** section identifies the
actual failed check; **SNAPSHOT ALIGNMENT** records NEMU's position. DUT has
no architectural next-PC port, so its commit PCs are shown separately from
NEMU's next PC. Disabled CSR checks are identified; CSR values are still shown.

Store mismatches include aligned address/data/byte-mask tuples. When NEMU
rejects a store without returning a reference tuple (for example an empty
store queue), emu reports the queue failure and DUT tuple without fabricating
NEMU values. Setup failures (library loading, ABI, image/options) use a separate
banner because a runtime comparison snapshot is not available yet.

Diagnostics use plain ASCII separators and fixed-width tables for terminals
and log files. Save both output streams with:

```bash
./build/emu -i apps/hello/build/hello.bin \
  --trace build/hello-retire.log --wave build/hello.fst > build/hello.log 2>&1
```

## What Is Checked

- PC before each retired instruction, and each nonzero GPR writeback.
- All 32 architectural GPRs after each retirement group, using architectural
  RAT + PRF, including multiple writes to the same register in one group.
- Privilege and the M/S CSR state exposed in the NEMU shadow-state ABI.
  `--no-check-csr` disables only CSR comparisons, not GPR/PC/store checks.
- Normal `sb/sh/sw/sd` stores against NEMU's store queue, including physical
  address, byte mask, and aligned data.
- Load/store type and effective virtual address using the reference's
  pre-instruction source registers and I/S immediates, including loads whose
  destination is `x0`. The pinned NEMU's signed-load query has early returns,
  so the harness does not rely on that incomplete query interface.
- Synchronous exceptions execute the faulting instruction in NEMU; xRET
  executes normally rather than being reported as an exception. Interrupt
  events use an explicit interrupt bit and cause, not `cause == 0`.

The event output is delayed until the same retirement group's architectural
RAT and CSR writes have taken effect. Comparing live speculative registers,
or merely comparing final program CRCs, would not provide this coverage.

MMIO loads/stores and pure reads of cycle/time/instret counters synchronize
only their external result and next PC. They are counted as `skipped` in
the result. Other GPR/CSR state is never copied wholesale to hide a
mismatch. Counter writes and other CSR instructions are not skipped.
MMIO is currently supported only for untranslated accesses (Bare or direct
M-mode). Translated MMIO fails explicitly, so a faulty mapping into the
device window cannot silently disable an ordinary DRAM comparison.

The current harness has no CLINT/PLIC/device model, external interrupt
injection, multicore, compressed/floating-point/vector/atomic ISA support,
checkpoint restore, or XiangShan squash/batch optimizations. It checks
retired store intent, not an independent final-memory flush of dirty caches.
Counters are not compared cycle-for-cycle. ISA/CSR differences found by
larger programs are reported as failures, not silently masked.

