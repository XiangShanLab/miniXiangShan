# CoreMark 性能对比

本目录提供 CoreMark 构建和运行平台，让 NutShell RV64 和 miniXiangShan RV64 在完全相同的外部
AXI4 DRAM、MMIO 和 console 平台上运行**同一份 RV64 CoreMark 机器码**，并
比较被测区间内的 `mcycle`、`minstret` 和 IPC。实验契约见 `METHODOLOGY.md`。

当前 CoreMark 结果：mini 为 3,255,350 周期，NutShell 为 3,406,098 周期，
mini 少用 4.43% 周期。计数条件及适用范围见 `METHODOLOGY.md`。

## 命令

性能对比命令是：

```bash
cd miniXiangShan
make perf
```

仅运行 miniXiangShan：

```bash
make coremark
# 等价入口：
bash src/main/run_coremark.sh
```

单核入口只准备 CoreMark，不获取或构建 NutShell，不需要 mill。
它复用下述 CoreMark 镜像、mini RTL 和 Verilator 构建流程，默认输出到
`perf/results/mini/coremark-mini.log` 与 `perf/results/mini/summary.txt`，
仅在完成签名、全部 CRC 和性能计数检查通过后生成摘要。
支持 `ITERATIONS`、`MAX_CYCLES`、`COREMARK_DIR`、`DEPS_DIR`、
`PERF_BUILD_DIR`、`OUT_DIR`、`SBT_BIN` 和 `FORCE_RTL_ELAB`。
`PERF_BUILD_DIR` 覆盖镜像与仿真器的共同构建目录。
已有 CoreMark 缓存版本或补丁不兼容时会报错并保留文件，可指定新的 `COREMARK_DIR`。

`make perf` 调用 `perf/run.sh`。不需要分别执行 clone、patch、build 和 run。
脚本可重复运行：依赖和补丁未变化时复用 `.deps/`；miniXiangShan 的 Scala
源码未变化时复用已生成的 `out-perf/` RTL，但 Verilator 模型和 CoreMark 镜像
仍会重新构建。

## 输入

默认输入无需设置，下面是可选的受控覆盖项：

| 变量 | 默认值 | 含义 |
| --- | --- | --- |
| `ITERATIONS` | `10` | CoreMark 迭代数；两边必须相同 |
| `TOTAL_DATA_SIZE` | `2000` | CoreMark 2K performance 配置 |
| `MAX_CYCLES` | `20000000` | 单个 RTL 仿真的超时周期数 |
| `DEPS_DIR` | `miniXiangShan/.deps` | 外部源码缓存目录 |
| `COREMARK_DIR` | `$DEPS_DIR/coremark` | 已准备的 CoreMark 源码目录 |
| `NUTSHELL_DIR` | `$DEPS_DIR/NutShell` | 已准备的 NutShell 源码目录 |
| `SBT_BIN` | `PATH` 或 `$HOME/sbt/bin/sbt` | sbt 可执行文件 |
| `MILL_BIN` | `mill` | mill 可执行文件 |
| `OUT_DIR` | `perf/results` | 日志与比较结果目录 |
| `FORCE_DEPS=1` | 未设置 | 强制重新检出并应用外部补丁 |
| `FORCE_RTL_ELAB=1` | 未设置 | 强制重新运行 sbt 生成 miniXiangShan RTL |

源码版本定义在 `deps.lock`。不要让两个 CPU 使用不同的 `ITERATIONS`、
`TOTAL_DATA_SIZE` 或 CoreMark 源码。

## 输出

终端依次显示：

- 五个构建/运行阶段的进度。
- CoreMark host 参考的 CRC 与自检结果。
- NutShell 的 CoreMark 原始打印和一行 `RESULT ...`。
- miniXiangShan 的 CoreMark 原始打印和一行 `RESULT ...`。
- `summary.txt` 的内容，包括周期比、退休指令比和 IPC 比。
- `commit-summary.txt` 的内容：按退休指令对齐后的窗口差值、load 等待分布、
  热点 load PC、直接 load-use 汇总和分区进度。

文件输出：

| 文件 | 内容 |
| --- | --- |
| `results/coremark-nutshell.log` | NutShell 的完整 CoreMark 打印和 RESULT 行 |
| `results/coremark-mini.log` | miniXiangShan 的完整 CoreMark 打印和 RESULT 行 |
| `results/summary.txt` | 最终比较结果和验证值 |
| `results/run-context.txt` | 时间、主机、依赖版本、编译参数和超时设置 |
| `results/commit-windows.tsv` | 每 4096 条退休指令一个窗口的周期、IPC、空窗和类别计数 |
| `results/commit-summary.txt` | 窗口差值的聚合归因和热点列表 |
| `.build/coremark/coremark-host.txt` | host CoreMark 参考输出 |
| `.build/coremark/coremark-rv64.{elf,bin,hex}` | RV64 裸机镜像 |
| `.build/obj-{mini,nutshell}/` | 两个 Verilator 模型及可执行文件 |
| `.build/traces/coremark-{nutshell,mini}.tsv` | 两侧原始 ROB 退休窗口轨迹 |
| `.deps/versions.txt` | 实际检出的仓库提交与 difftest 提交 |

`RESULT` 行的字段：

```text
status=ok|timeout
cycles=mcycle 测量值
instret=minstret 测量值
ipc=instret/cycles
signature=CoreMark 完成签名
checksum=CoreMark crcfinal
sim_cycles=从复位开始的仿真周期数（不参与比较）
```

## `make perf` 实际做了什么

### 1. 准备外部源码

`scripts/fetch_deps.sh` 读取 `deps.lock`：

- 克隆或更新 NutShell 到指定提交，并初始化 `difftest` 子模块。
- 克隆或更新 CoreMark 到 `v1.01` 对应的精确提交
  `cfa9ab377835911f23d9b0831c7be302ed1f58de`，再校验框架和算法文件的
  SHA-256。这里不使用会移动的 `HEAD`。
- 给 NutShell 依次应用四个补丁，并安装
  `platform/nutshell/NutShellPerfTop.scala`：
  - `0001-add-perf-board.patch`：注册性能实验顶层；
  - `0002-fix-ooo-perfcnt-and-difftest.patch`：适配 Chisel 7 并关闭只属于
    差分测试/ILA 的观测逻辑；
  - `0003-fix-ooo-retire-waw.patch`：修正 OOO 双发射退休时同一周期写同一
    架构寄存器的优先级；
  - `0004-add-retire-trace.patch`：导出只读 ROB 退休轨迹，用于两侧指令序列对齐。
- 给 CoreMark 应用 `patches/coremark/0001-report-results.patch`。

每个外部仓库先被强制切回固定版本并清理工作树，因此重复运行不会叠加补丁。
缓存键覆盖依赖版本、校验和、所有补丁和 NutShell 适配文件。

### 2. 构建 CoreMark

`scripts/build_coremark.sh` 使用外部 CoreMark 的框架与算法文件，加上本仓库
`platform/coremark/` 中的裸机 port，生成：

- host 参考程序，用于确认 seed 与 sub-CRC 正确；
- `rv64im_zicsr/lp64` 镜像供本次对比中的 miniXiangShan 和 NutShell 共同使用。

host 只要缺少 `seedcrc=0xe9f5`、任一 sub-CRC 或 `errors=0` 就立即失败。

### 3. 构建 miniXiangShan

`scripts/build_mini.sh` 执行 `minixiangshan.CoreGen perf` 生成
`out-perf/*.v`，再用 Verilator 编译 `MiniPerfTop`、共享平台和 testbench。
`simu`、`fpga` 和 `perf` 均使用 RV64；性能平台使用 Bare 地址模式。

### 4. 构建 NutShell

`scripts/build_nutshell.sh` 在 `.deps/NutShell` 中执行 mill
`BOARD=perf CORE=ooo`，输出平铺的 `NutShellPerfTop` RTL，再用 Verilator
编译另一个模型。`CORE=ooo` 会选择 NutShell 的双发射乱序后端，与
miniXiangShan 的乱序核处于同一实验类别。补丁只修复该配置在当前上游版本中
无法 elaboration、以及双退休写同一寄存器时的正确性问题，不改变 cache 容量、
分支预测器、发射宽度或存储时序。

### 5. 运行、校验和比较

`scripts/run_compare.sh` 依次启动两个模型，通过
`+dram_init=coremark-rv64.hex` 给两边加载同一份镜像。两边进入 CoreMark
`start_time()` 时清零计数器，在 `stop_time()` 采样；CoreMark 完成自检后把
结果写到 `0x4000_f000` MMIO，并使仿真结束。

只有以下条件全部通过才输出比较数据：

1. 两边仿真都进入 `status=ok`，没有超时。
2. 两边的 `seedcrc`、`crclist`、`crcmatrix`、`crcstate`、`crcfinal`
   都与 host 参考一致。
3. 两边 signature 都是 `0x434d524b`，MMIO 中的 crcfinal 也一致。
4. CoreMark `errors=0`。

比较公式：

```text
cycle_ratio   = mini.cycles / nutshell.cycles
instret_ratio = mini.instret / nutshell.instret
ipc_ratio     = mini.ipc / nutshell.ipc
```

三个比值均为同一次运行内的派生数据。两边执行同一份 RV64 机器码，因此
`instret` 基本一致；当前结果相差 5 条，来自两侧性能计数器的采样边界。
`cycles` 和 IPC 的差异反映微架构实现，而不是 ISA 或编译器差异。

## 提交窗口轨迹

默认比较会同时采集两侧 ROB 的架构退休窗口。轨迹从 CoreMark `iterate` 的
第一条退休指令开始，按退休顺序持续到 `stop_time` 之前，不按周期采样。两边
都用固定的三路端口；NutShell 最多两路，第三路恒为无效。采集端口只读，不参与
流水线控制、cache/MSHR 状态或 MMIO 请求，因此不会改变被测核的时序。

每个窗口包含 4096 条退休指令。`TRACE_WINDOW` 可调整窗口大小；`TRACE=0` 可关闭
轨迹采集。日常比较仍只需要 `make perf`。窗口字段和聚合归因定义见
`METHODOLOGY.md` 第 7 节。
