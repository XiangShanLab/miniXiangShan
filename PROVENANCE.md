# Provenance

## miniXiangShan

- Source: XiangShanLab `xiangshan-programming-practice/miniXiangShan`
- Imported revision: `ed0d68f83e7234d463cc5344db96d7af73de0b2c`
- Subsequent local fixes are part of this repository's miniXiangShan source.

Generated RTL, Verilator objects, VCD files, Scala caches, benchmark images and
result logs are not source and are ignored.

## External performance dependencies

Third-party source is not vendored. `perf/deps.lock` records the repositories
and revisions; `perf/scripts/fetch_deps.sh` fetches them into `.deps/`:

- NutShell: `https://github.com/OSCPU/NutShell.git`,
  commit `a854aead78cf3c5e54a76f8724fe182d4de914d3`.
- NutShell `difftest`: initialized by NutShell's own submodule metadata.
- CoreMark: `https://github.com/eembc/coremark.git`, tag `v1.01`, commit
  `cfa9ab377835911f23d9b0831c7be302ed1f58de`; the framework and algorithm
  files are content-checked using `perf/deps-checksums.sha256`.

The exact resolved commits of a run are written to
`perf/.build/dependencies.txt` and copied into
`perf/results/run-context.txt`.

Performance-only changes are kept as small, reviewable files:

- `perf/platform/nutshell/NutShellPerfTop.scala`
- `perf/patches/nutshell/0001-add-perf-board.patch`
- `perf/patches/nutshell/0002-fix-ooo-perfcnt-and-difftest.patch`
- `perf/patches/nutshell/0003-fix-ooo-retire-waw.patch`
- `perf/patches/coremark/0001-report-results.patch`

Rationale and change boundaries are documented in `perf/patches/README.md`.
