# 应用构建

每个 `apps/<name>/` 都是独立应用目录。进入目录执行 `make` 只构建应用，
完成后打印手动运行示例：

```bash
cd hello
make
../../build/emu -i build/hello.bin
```

`coremark` 示例：

```bash
cd coremark
make ITERATIONS=10
../../build/emu -i build/coremark.bin
```

该应用首次构建时从 `deps.lock` 的官方 URL 下载固定版本，应用本目录中的
`report-results.patch`；源码保存在根目录 `.deps/coremark/`。
`coremark-host` 是供对比流程执行的本机参考程序，应用构建不会运行它。

## 输出与参数

每个应用自己的 `build/` 包含：

| 文件 | 用途 |
| --- | --- |
| `<name>.elf` | ELF、符号信息 |
| `<name>.bin` | 原始二进制，传给 `emu -i` |
| `<name>.hex` | 64 位小端 readmemh 镜像，供对比平台加载 |
| `<name>.txt` | 反汇编 |
| `build-info.txt` | 应用编译参数与输入指纹 |

默认 ISA 为 `rv64im_zicsr_zifencei`，ABI 为 `lp64`，链接和入口地址为
`0x80000000`。不使用压缩、原子、浮点或向量指令。
`CROSS_COMPILE` 可指定工具链前缀，默认 `riscv64-unknown-elf-`；也可分别设置
`RISCV_CC`、`RISCV_OBJCOPY`、`RISCV_OBJDUMP`。`make clean` 只删除当前应用的产物。

在仓库根目录执行 `make clean-apps`，可统一清理所有应用的构建产物。
该入口自动发现 `apps/*/Makefile`，逐个调用应用的 `clean` 规则；新增应用后
无需修改根目录 Makefile。

## 新增应用

新建目录，例如 `apps/myapp/`，编写 `main.c` 和 Makefile：

```make
NAME := myapp
SRCS := main.c
HEADERS :=
include ../common/app.mk
```

`common/` 提供清零 BSS、设置栈、调用 `main` 的启动代码，以及最小字符串函数。
`main` 返回后以 `ebreak` 和返回值 `a0` 结束 emu。需要控制台和性能计数时，可像
hello 一样加入 `../common/runtime.c` 和 `../common/include/mini.h`。
把额外的头文件和影响编译的配置列在 `HEADERS`、`BUILD_INPUTS` 中。

## 让应用参加性能对比

对比平台需要应用写入统一的性能 MMIO 寄存器；仅以 `ebreak` 结束的程序
可在 emu 运行，不能直接用性能平台测量。
hello 的 `mini_perf_begin()` / `mini_perf_end()` 提供了完整示例：

1. 在被测区间前清零 `mcycle`、`minstret`。
2. 结束时采样计数器并上报校验和。
3. 自检成功写入签名 `0x434d524b`，失败写入其他签名。
4. 最后通过 `0x4000f030` 发出完成通知。

在应用目录添加 `compare.json`：

```json
{
  "image": "build/myapp.bin",
  "elf": "build/myapp.elf",
  "hex": "build/myapp.hex",
  "expected_checksum": 5050,
  "trace_start": "benchmark",
  "trace_stop": "mini_perf_end"
}
```

路径相对于应用目录。`expected_checksum` 必须是程序独立计算出的预期结果；
也可以提供 `validator` 校验脚本，接受 `--log <RTL日志>`。若有 `reference`
本机可执行文件，对比工具先执行它并记录输出，再向 validator 传入
`--reference <参考日志>`；CoreMark 使用此方式检查所有 CRC。

退休轨迹使用 ELF 的 `trace_start`、`trace_stop` 符号确定区间。未提供这些符号
时，运行对比需指定 `RETIRE_TRACE=0`。应用编译完成后：

```bash
cd ../../compare
make run APP=myapp
```
