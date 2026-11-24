# miniXiangShan 与 NutShell 性能对比

所有对比命令在本目录执行。应用在 `../apps/<name>/` 独立编译，对比工具读取
该目录的 `compare.json`，将同一份镜像交给两个 CPU。

```bash
# 先在应用目录编译
cd ../apps/coremark
make ITERATIONS=10

# 再进入对比目录
cd ../../compare
make build
make run APP=coremark
```

`make build` 只准备 NutShell 并构建两个性能仿真器，生成
`build/mini-emu` 和 `build/nutshell-emu`。两个仿真器在运行时通过
`+dram_init=<image.hex>` 加载镜像，构建阶段不依赖某个应用。

`make run APP=<name>` 首先检查应用产物是否齐全、BIN 与 HEX 是否一致，再增量
构建并运行。应用缺失时会提示到对应目录编译。直接 `make` 显示帮助。

```bash
make run APP=hello
make run APP=coremark MAX_CYCLES=20000000
make run APP=coremark RETIRE_TRACE=0
make run APP=coremark TRACE_WINDOW=8192 JOBS=8
```

mini 的 BPU、Load Queue 和 L2 预取策略使用
`../src/main/scala/config/Core.scala` 中的 `CoreConfigKeys` 配置，修改后重新构建。

## 配置与结果

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `APP` | `coremark` | `../apps/` 下的应用目录名 |
| `MAX_CYCLES` | `20000000` | 从复位开始的单边仿真周期上限 |
| `RETIRE_TRACE` | `1` | 采集和分析双方的 ROB 退休轨迹 |
| `TRACE_WINDOW` | `4096` | 每个分析窗口的退休指令数 |
| `JOBS` | `8` | Verilator/C++ 构建并行度 |
| `SBT/MILL/VERILATOR` | 自动查找 | 构建工具 |
| `CROSS_COMPILE` | `riscv64-unknown-elf-` | 退休分析所用 binutils 前缀 |
| `DEPS_DIR` | 根目录 `.deps/` | 外部源码缓存 |

应用迭代次数等参数属于应用编译，例如 `make -C ../apps/coremark ITERATIONS=20`；
对比命令直接使用已有产物，不能通过运行参数改变镜像中的迭代次数。

每次运行输出到 `results/<app>/`：

| 文件 | 内容 |
| --- | --- |
| `nutshell.log`、`mini.log` | 程序输出与性能计数 |
| `reference.log` | 可选的本机参考程序输出 |
| `summary.txt` | 校验通过后生成的周期、指令数、IPC 与双方比值 |
| `run-context.txt` | 镜像/仿真器 SHA256、依赖版本、应用编译参数和运行配置 |
| `nutshell.tsv`、`mini.tsv` | 开启退休轨迹时生成的原始记录 |
| `commit-windows.tsv`、`commit-summary.txt` | 轨迹对齐与窗口分析 |

应用必须完成自检、写入成功签名、给出有效计数器和预期校验和。
CoreMark 还会逐项检查 host 与 RTL 的 sub-CRC。开启退休轨迹时，双方必须完整
经过指定区间并具有一致的退休指令序列。任一条件失败都会非零退出，
本次运行不生成有效的 `summary.txt`。

## 平台与边界

两边使用 `platform/rtl/Axi4PerfPlatform.v` 提供相同的 16 MiB DRAM、MMIO
及 console 时序。NutShell 固定为 `CORE=ooo`，使用自己的流水线和 Cache。
本目录保留 NutShell 适配层、补丁及其原因，见 [patches/README.md](patches/README.md)。
外部依赖按固定提交准备；遇到未知本地修改会报错并保留文件。

性能计数覆盖应用定义的被测区间，`sim_cycles` 仅用于诊断。
性能平台的应用自检与 NEMU 逐指令差分有不同职责；差分运行使用根目录
`../build/emu -i ../apps/<name>/build/<name>.bin`。
CoreMark 的计数口径、公平性和退休分析限制见 [METHODOLOGY.md](METHODOLOGY.md)。

## 清理

```bash
make clean                     # 仅删除本目录的 build/
make clean-results APP=coremark # 仅删除该应用的对比结果
```

也可以在仓库根目录执行 `make clean-compare`，删除 `compare/build/` 下的构建产物。

应用产物由应用目录自己的 `make clean` 管理。第三方缓存由根目录
`make clean` 统一删除。编辑器配置保留；生成的 sbt 版本文件也由根目录
`make clean` 删除，下次构建自动生成。
