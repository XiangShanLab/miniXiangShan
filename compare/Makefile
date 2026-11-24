# Run from this directory. Apps are built separately in ../apps/<name>/.
.DEFAULT_GOAL := help
.NOTPARALLEL:
SHELL := /bin/bash
export BUILD_DIR ?= $(CURDIR)/build
export DEPS_DIR ?= $(abspath ../.deps)
export JOBS ?= 8
export APP ?= coremark
export MAX_CYCLES ?= 20000000
export RETIRE_TRACE ?= 1
export TRACE_WINDOW ?= 4096
export CROSS_COMPILE ?= riscv64-unknown-elf-

.PHONY: help build run check-app clean clean-results
build:
	bash scripts/build.sh

check-app:
	python3 scripts/run.py --check

run: check-app build
	python3 scripts/run.py

clean:
	bash scripts/clean.sh

clean-results:
	python3 scripts/run.py --clean-results

help:
	@echo "make build                 Build both CPU simulators (no app required)"
	@echo "make run APP=coremark      Run both CPUs with ../apps/coremark/build/"
	@echo "make run APP=hello         Select another compiled app"
	@echo "make clean                 Remove comparison build products"
	@echo "make clean-results APP=... Remove only the selected app's results"
	@echo "Options: JOBS=N MAX_CYCLES=N RETIRE_TRACE=0/1 TRACE_WINDOW=N"
	@echo "Tools: SBT, MILL, VERILATOR, CROSS_COMPILE; cache: DEPS_DIR"
	@echo "Build the app first: cd ../apps/coremark && make ITERATIONS=10"
