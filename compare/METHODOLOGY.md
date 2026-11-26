# CoreMark RTL 对比方法

## 1. 研究问题

在相同 CoreMark 输入、相同外部存储器和相同 MMIO 时序下，比较完整
miniXiangShan 与完整 NutShell 的端到端周期数。该实验回答“两个完整核在这一
受控负载上的运行时间差异”，不把差异归因于单个模块。

## 2. 配置与固定条件

- CoreMark 1.0.1（`eembc/coremark` 的 `v1.01` 提交
  `cfa9ab377835911f23d9b0831c7be302ed1f58de`），2K performance 配置，
  `TOTAL_DATA_SIZE=2000`。
- 默认 `ITERATIONS=10`；两边必须使用相同值。
- 同一版 CoreMark 算法源码和同一份 `link.ld`、`start.S` 契约。
- 同一份 `Axi4PerfPlatform.v`、同一个 Verilator testbench 协议。
- DRAM 基址 `0x8000_0000`，16 MiB 窗口；MMIO 基址 `0x4000_f000`。
- 外部平台单 outstanding，读首拍 1 cycle，后续每拍 1 beat。
- NutShell 通过 `make ... NUTSHELL_CORE=ooo/inorder` 选择原生 RV64 配置，
  默认 `ooo`（双发射乱序后端），也可选择 `inorder`（单槽顺序提交）。
  miniXiangShan 的 `compare` 配置使用 RV64IM 乱序核。
- 两边加载同一份 `apps/coremark/build/coremark.hex`，因此执行的指令流和地址布局相同。

两边保留各自内部 L1/L2、替换策略、MSHR、预取器和流水线。实验统一的是外部
存储器和 MMIO，不是内部 cache 或微架构。

每次运行只比较 miniXiangShan 与所选 NutShell 配置，配置记录在
`summary.txt` 和 `run-context.txt` 中。结果分别写入
`results/coremark/ooo/` 和 `results/coremark/inorder/`；比较两种果壳时须保持
应用镜像和运行参数一致。

## 3. 计数口径

CoreMark 的 `start_time()` 清零 `mcycle` 和 `minstret`，`stop_time()` 在
`iterate()` 结束后读取。因此：

- `cycles` 覆盖 CoreMark 实际迭代区间，不含初始化、打印和退出。
- `instret` 覆盖同一区间。
- `ipc = instret / cycles`。
- `sim_cycles` 从复位开始计数，只用于诊断仿真进度，不参与比较。

CoreMark 报告通过 `0x4000_f038` 输出，且发生在计时区间之外。报告中的
`Total time` 和 `Finished` 按 1 GHz 将 `mcycle` 归一化为毫秒，仅为可读性
约定，不是实测频率。

## 4. 功能门禁

host 参考必须先得到：

```text
seedcrc=0xe9f5
crclist=0xe714
crcmatrix=0x1fd7
crcstate=0x8e3a
errors=0
```

`crcfinal` 随总迭代数变化，因此从当次 host 日志读取，不在脚本中写死。
NutShell 和 miniXiangShan 都必须满足：

- 所有 sub-CRC 与 host 相同；
- MMIO crcfinal 与 host 相同；
- signature 为 `0x434d524b`；
- 仿真在 `MAX_CYCLES` 内完成。

任一条件失败，脚本不会生成比较数据，并以非零状态退出。

## 5. 比较指标

```text
cycles_ratio    = mini.cycles / nutshell.cycles
instret_ratio   = mini.instret / nutshell.instret
ipc_ratio       = mini.ipc / nutshell.ipc
```

三个字段均为同一次运行内的派生比值。两边使用同一份 RV64 镜像，正常情况下
`instret_ratio` 应接近 1；计时边界附近的计数器采样差异取决于核配置，
不能假定两种 NutShell 配置具有相同的计数偏差。IPC 是派生量。

## 6. 公平性与边界

- 两边已统一为 RV64 和同一份机器码，不再存在 RV32/RV64 指令流差异。
- 两边 cache、预取器、MSHR 和流水线不同，这是被比较对象的一部分。
  `ooo` 比较两个乱序核，`inorder` 比较 miniXiangShan 乱序核与 NutShell
  顺序核；各配置均保留上游对应的微架构设置。
- NutShell 外部连接有 MEM/MMIO AXI mux；miniXiangShan 原生 AXI4 直连平台。平台时序相同，但内部请求序列仍可能不同。
- 16 MiB 单 outstanding 平台不是完整 SoC 互联或 DRAM 控制器模型。
- 负载只覆盖 CoreMark；不能外推到 OS、中断、多线程或真实应用。
- CoreMark 控制台输出会改变镜像代码布局，因此不要和旧版镜像或不同打印配置
  的数值混比。
- 固定 10 次迭代是 RTL 可执行性选择，不是 CoreMark 认证运行，不能发布为
  官方 CoreMark/MHz 分数。
- 上游 CoreMark 的“至少运行 10 秒”有效性检查在补丁中被移除，只因为 RTL
  固定迭代实验无法满足该门槛；计时区间、CRC 校验和 benchmark 算法均未改变。

`summary.txt` 中的数据字段为：

```text
cycles_ratio=<mini.cycles / nutshell.cycles>
instret_ratio=<mini.instret / nutshell.instret>
ipc_ratio=<mini.ipc / nutshell.ipc>
cycles_per_iteration: NutShell=<value> mini=<value>
```

## 7. 架构退休窗口归因

端到端周期数只说明差异大小，不能说明差异在流水线的哪个位置累积。因此同一
次运行另外采集两侧的架构退休窗口：

- NutShell `ooo` 取 ROB 的 `retireATerm` / `retireMultiTerms` 对应退休槽位；
- NutShell `inorder` 取 WBU 的 `io.in.valid` 与对应 PC/指令；WBU 始终
  ready，每周期最多提交一条指令，其余轨迹槽位填零；
- miniXiangShan 取 ROB 的 `commit.valid` 对应提交槽位；
- 不取执行单元写回。写回是乱序的，不能直接与另一侧的架构退休顺序对齐。

轨迹以退休指令序号对齐，而不是以周期对齐。第 `i` 条退休指令在某一侧的退休
周期记为 `C_i`，则

```text
gap_i          = C_i - C_(i-1) - 1
window_cycles  = C_end - C_previous_end
window_delta   = mini.window_cycles - nutshell.window_cycles
```

`gap_i > 0` 表示相邻两条退休指令之间至少有 `gap_i` 个周期没有退休。把
`gap_i` 按当前退休指令类别汇总，就得到 `GAP_DELTA_BY_CURRENT_CLASS`：类别为
`load` 时，它表示 load 自身从可以成为退休窗口头到真正退休之间的等待，而不是
load 后消费者的依赖等待。后者单独归入 `DIRECT_LOAD_USE`：只有当前指令读取
紧邻的前一条 load 的 `rd` 时才计数。

`LOAD_GAP_PROFILE` 按每个 load 自身的 `gap` 分桶；`TOP_LOAD_PCS_BY_GAP_DELTA`
按 load PC 汇总两侧等待差；`GAP_DELTA_BY_REGION` 从第一条退休指令开始，每
262144 条指令给出一次累计进度。`commit-windows.tsv` 中另有
`cumulative_delta_cycles`，可以直接观察差异从第一条窗口起如何累积。

该轨迹只回答“差异发生在哪类退休指令上”。它不包含 load 地址、L1 命中位、
MSHR 表项或 L2 命中位，因此不能单靠它把长等待严格区分为 L1 miss、MSHR
replay 或 load queue 发射等待。这些原因需要另加只读事件计数器验证；在增加
计数器前，不应把窗口归因写成缓存命中率结论。
