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
- `make perf` 能从本仓库重新获取 NutShell/CoreMark 并独立运行。

旧目录替换涉及父仓库删除操作，应由父仓库维护者确认后执行。
