# miniXiangShan
#
#   make / make sim : 生成仿真版 RTL
#   make fpga       : 生成上板版 RTL
#   make perf       : 完整构建并运行 CoreMark 对比（唯一性能入口）
#   make clean      : 删除全部生成物和外部依赖缓存，恢复初次克隆状态

.PHONY: all sim fpga perf test-mmu test-pipeline clean help
.DEFAULT_GOAL := sim

SHELL := /bin/bash
SBT ?= $(shell command -v sbt 2>/dev/null || echo $(HOME)/sbt/bin/sbt)

RTL_DIR := ./out
TOP_V := $(RTL_DIR)/core_top.v
SCALA_FILES := $(shell find ./src/main/scala -name "*.scala")
CLEAN_PATHS := .deps .bloop .bsp .idea .sbt-global .sbt-run .ivy2 \
  target project/target project/project project/.bloop project/metals.sbt \
  out out-perf build perf/.build project/

all: sim

sim:
	$(SBT) "runMain minixiangshan.CoreGen simu"

fpga: $(TOP_V)

$(TOP_V): $(SCALA_FILES)
	$(SBT) "runMain minixiangshan.CoreGen fpga"

perf:
	./perf/run.sh

test-mmu:
	$(SBT) --server -batch "testOnly minixiangshan.mmu.*Spec"

test-pipeline:
	$(SBT) --server -batch "testOnly minixiangshan.mmu.*Spec minixiangshan.mem.LoadQueueSpec minixiangshan.backend.LoadWakeupSpec minixiangshan.frontend.IFUSpec minixiangshan.backend.decode.ImmGenSpec"

clean:
	@echo "Removing generated files and cached external sources..."
	rm -rf $(CLEAN_PATHS)
	@# Metals may hold NFS file handles while the editor is open and recreate this cache.
	@rm -rf .metals 2>/dev/null || true
	rm -f perf/results/*.log perf/results/summary.txt perf/results/run-context.txt
	find . -type f \( -name '*.vcd' -o -name '*.class' \) -delete

help:
	@echo "make / make sim : 生成仿真版 RTL -> $(RTL_DIR)"
	@echo "make fpga       : 生成上板版 RTL -> $(RTL_DIR)"
	@echo "make perf       : 构建并运行 NutShell vs miniXiangShan CoreMark 对比"
	@echo "make test-mmu   : 运行 RV32/RV64 MMU 功能与流水线回归"
	@echo "make test-pipeline : 运行访存、提前唤醒和取指流水线回归"
	@echo "make clean      : 删除所有 .gitignore 生成物，恢复初次克隆状态"
