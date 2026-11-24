# 在 XiangShanLab 中接入

本仓库是 miniXiangShan 与 CoreMark 性能工程的唯一源码来源。XiangShanLab
只记录本仓库的提交或工作区链接，不复制核源码，也不读取旧的 `xs-env` 路径。

## 推荐：Git submodule

先为本仓库配置远端并提交，然后在 XiangShanLab 中执行：

```bash
cd /path/to/XiangShanLab
git rm -r xiangshan-programming-practice/miniXiangShan
git submodule add <THIS_REPOSITORY_URL> \
  xiangshan-programming-practice/miniXiangShan
git submodule update --init --recursive
```

之后父仓库只保存一个 gitlink。更新流程是：

```bash
cd xiangshan-programming-practice/miniXiangShan
git fetch
git checkout <NEW_COMMIT>
cd /path/to/XiangShanLab
git add xiangshan-programming-practice/miniXiangShan
```

## 本地验证：符号链接

远端尚未建立时，可以临时链接工作区：

```bash
cd /path/to/XiangShanLab/xiangshan-programming-practice
mv miniXiangShan miniXiangShan.old
ln -s ../../miniXiangShan miniXiangShan
```

请在 XiangShanLab 根目录确认相对路径；上面的 `../../` 假设
`xiangshan-programming-practice/miniXiangShan` 位于父仓库两级目录下。链接仅
用于本地验证，长期版本管理必须使用 submodule。

## 接入检查

- XiangShanLab 中不存在第二份可编辑的 miniXiangShan 源码。
- 本仓库的构建和性能脚本不引用 `XiangShanLab` 或 `xs-env`。
- 根目录 `make emu` 能独立构建仿真器和 NEMU。
- `apps/coremark/` 的 `make` 能独立构建镜像，`compare/` 中的
  `make run APP=coremark` 能获取 NutShell 并运行对比。

旧目录替换涉及父仓库删除操作，应由父仓库维护者确认后执行。

## 核对外总线

`core_top` 原生输出一组 AXI4 Full master：64 位地址、64 位数据、
4 位 ID、8 位 LEN、1 位 LOCK，无 WID。提供 4 位 ARQOS/AWQOS 和
1 位各通道 USER；输出 QoS/USER/LOCK 当前为零，响应 USER 不参与处理。
未使用可选 REGION 信号。

I-Cache 与 PTW 共享 L2 读入口，D-Cache 使用独立 native 入口，最终均由
L2Bridge 驱动同一组 AXI4。外部全局单 outstanding，读写串行，ID 固定为零。
64 字节 cache line 使用 8 拍 INCR burst；uncache 使用单拍 8 字节传输，
写字节由 WSTRB 选择。AW/W 独立握手，写完成等待 B 响应。

比较平台 `MiniPerfTop` 直接连接 AXI4，不再使用 AXI3 转换层。该平台地址图
为 32 位，连接时截取地址低 32 位，并断言高 32 位为零。
