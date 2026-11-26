# Provenance

## miniXiangShan

- Source: XiangShanLab `xiangshan-programming-practice/miniXiangShan`.
- Imported revision: `ed0d68f83e7234d463cc5344db96d7af73de0b2c`.
- Subsequent fixes and the independent build layout belong to this repository.

Generated RTL, simulator objects, application images, waveforms and result logs
are ignored. `project/build.properties` is also generated and ignored;
`scripts/elaborate.sh` creates it with sbt 2.0.2 when absent. Root `make clean`
removes it, and the next build recreates it.

## NEMU reference

`difftest/deps.lock` pins the public `OpenXiangShan/NEMU` repository at
`a48a11f208ff81ad765591d83271d1b339fb3861`. `scripts/build-ref.sh` builds the
integer-only RV64 shared library inside `.deps/nemu-ref/`, then installs it in
`build/ref/`. The runtime does not import another local source checkout.

## Applications

`apps/coremark/deps.lock` pins `https://github.com/eembc/coremark.git` at
`cfa9ab377835911f23d9b0831c7be302ed1f58de` (v1.01).
`apps/coremark/checksums.sha256` checks the pristine framework and algorithm files.
`apps/coremark/report-results.patch` adapts reporting and the fixed-iteration
validity gate; the workload algorithms are unchanged. The bare-metal port is
in `apps/coremark/src/`, with startup/linker/string support in `apps/common/`.

CoreMark is fetched independently of CPU and NutShell builds. The application
records compiler flags and an input fingerprint in its own `build/build-info.txt`.

## NutShell comparison

`compare/nutshell.lock` pins `https://github.com/OSCPU/NutShell.git` at
`a854aead78cf3c5e54a76f8724fe182d4de914d3`. Its own `difftest` submodule is
initialized at the revision recorded by the upstream checkout.

Adaptations are kept in:

- `compare/platform/nutshell/NutShellPerfTop.scala`;
- `compare/patches/nutshell/0001-add-perf-board.patch`;
- `compare/patches/nutshell/0002-fix-ooo-perfcnt-and-difftest.patch`;
- `compare/patches/nutshell/0003-fix-ooo-retire-waw.patch`;
- `compare/patches/nutshell/0004-add-retire-trace.patch`;
- `compare/patches/nutshell/0005-add-inorder-retire-trace.patch`.

Patch reasons and functional boundaries are documented in
`compare/patches/README.md`. Source preparation never resets unknown local
changes. Resolved revisions and adaptation fingerprints are recorded in
`compare/build/dependencies.txt`; each run saves them with image and simulator
SHA256 values and the selected `NUTSHELL_CORE` in
`compare/results/<app>/<core>/run-context.txt`.
