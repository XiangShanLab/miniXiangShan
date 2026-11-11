# Dependency patches

The upstream repositories are never edited in place by hand.  On every cold
dependency fetch, they are checked out at `perf/deps.lock`, reset to a clean
tree, and these patches are applied.  Re-running the experiment with an
unchanged lock file and unchanged patches reuses the prepared checkout.

## NutShell

Files changed:

- `perf/platform/nutshell/NutShellPerfTop.scala` is copied into
  `src/main/scala/sim/`.  It is our adapter, not a copy of NutShell core logic.
- `0001-add-perf-board.patch` registers `BOARD=perf` in
  `src/test/scala/TopMain.scala`.
- `0002-fix-ooo-perfcnt-and-difftest.patch` makes the pinned upstream OOO path
  elaborate with Chisel 7 and disables only difftest/ILA instrumentation for
  the standalone performance top.
- `0003-fix-ooo-retire-waw.patch` fixes the architectural register write
  priority when both retire banks write the same register in one cycle.
- `0004-add-retire-trace.patch` exports the ROB retirement slots to the
  performance top through read-only `BoringUtils` taps.  It does not drive or
  modify any pipeline, cache, TLB, or memory signal.

Why this is needed:

- Upstream emits a nested SoC, while the Verilator harness needs a flat top
  with one AXI4 slave port and the shared performance-output pins.
- NutShell has two AXI4 masters (`mem` and `mmio`).  The adapter serializes
  them into the same external platform used by miniXiangShan.  This changes
  only the external connection; NutShell's pipeline, caches, and branch
  predictor are untouched.
- `BOARD=perf` selects that adapter during Chisel elaboration.  The normal
  `sim` and FPGA flows remain available.

Why `0002` is needed:

- The pinned upstream `CORE=ooo` revision does not elaborate with the current
  Chisel/firtool toolchain.  Upstream `BoringUtils.addSource` calls pass
  expressions or implementation-specific signals that are no longer legal
  annotation sources.  The patch wraps those sources in `WireInit`, exactly as
  in upstream commit `6d900cd` ("misc: wrap all sources of BoringUtils with
  Wire"), and removes one hit-counter source that has no sink in the
  standalone perf top.
- `NutCoreConfig.EnableDifftest` is added so the perf top can omit the
  reference-model taps while preserving the normal NutShell defaults.  The
  gated blocks only feed difftest/ILA observation; they do not drive the
  pipeline, caches, branch predictor, or memory requests.
- The `LSUADDR` sink is moved into the in-order branch because the OOO backend
  does not provide that debug pin.  This is also observation-only.
- The resulting RTL keeps `CORE=ooo` and the same core/cache/TLB settings.  No
  functional timing path is intentionally changed by `0002`.

Why `0003` is needed:

- NutShell's OOO ROB retires up to two instructions per cycle and drives two
  architectural register-file writes.  When both banks write the same
  destination, the younger bank (bank 1) must win.
- In the pinned revision the two unconstrained `rf.write` calls are lowered to
  a multi-write `Mem`; the generated priority can let the older bank (bank 0)
  win.  CoreMark starts with `auipc t0` followed by `addi t0,t0,...`, both
  retired together, so the architectural `x5` is left with the `auipc` value.
  The BSS-clearing loop then never terminates.
- `0003` suppresses the older bank's write when both banks target the same
  non-zero destination.  Different destinations still use both write ports;
  writes to `x0` are suppressed.  This restores the ISA-required program order
  without serializing normal dual-retire register writes.

Why `0004` is needed:

- The comparison needs architectural retirement order, not CDB writeback
  order.  The patch therefore taps the ROB's `retireATerm` and
  `retireMultiTerms` slots after retirement eligibility has been decided.
- NutShell has two retirement slots.  The performance top expands them to the
  shared three-lane trace interface used by the Verilator harness; the unused
  lane is tied low.
- These signals are observation-only.  The trace does not feed back into the
  core and does not alter instruction timing.

## CoreMark

File changed:

- `0001-report-results.patch` changes only `core_main.c`, CoreMark's framework
  file.  The algorithm files (`core_list_join.c`, `core_matrix.c`,
  `core_state.c`, and `core_util.c`) are not patched.

Why this is needed:

- The RTL harness needs machine-readable `mcycle`, `minstret`, CRC, and
  completion values.  It cannot scrape a UART or depend on an operating
  system.
- The patch calls `coremark_report()` after CoreMark has validated its own
  sub-CRCs.  That function is implemented in our platform port and writes the
  values to the shared MMIO block.
- The patch removes CoreMark's 10-second validity gate because this RTL
  experiment intentionally uses a fixed, smaller iteration count.  It also
  makes the printed elapsed value use the same 1 GHz / millisecond display
  convention as the timing port and drops the derived throughput print.  It
  does not change `mcycle`, `minstret`, CRC checks, or benchmark algorithms.

The bare-metal port (`start.S`, `link.ld`, `minilibc.c`, `core_portme.c`, and
`core_portme.h`) lives under `perf/platform/coremark/`.  Those are platform
adaptation files, allowed by CoreMark's porting rules, and are not a copy of
the benchmark algorithm.
