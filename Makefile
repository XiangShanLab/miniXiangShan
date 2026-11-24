# Build the CPU simulator here; build workloads inside apps/<name>/.
.DEFAULT_GOAL := emu
SHELL := /bin/bash

export BUILD_DIR ?= $(CURDIR)/build
export DEPS_DIR ?= $(CURDIR)/.deps
export JOBS ?= 8
export WITH_DIFFTEST ?= 1
export WITH_WAVE ?= 1

.PHONY: emu clean clean-all clean-apps clean-compare help
emu:
	bash scripts/build-emu.sh

clean:
	bash scripts/clean.sh

clean-all:
	$(MAKE) clean
	$(MAKE) clean-apps
	$(MAKE) clean-compare
	rm -rf -- "$(CURDIR)/out" "$(CURDIR)/out-perf" "$(CURDIR)/obj_dir" "$(CURDIR)/test_run_dir"

clean-apps:
	@set -e; for app in $(patsubst %/Makefile,%,$(wildcard apps/*/Makefile)); do \
	  $(MAKE) -C "$$app" clean BUILD_DIR="$(CURDIR)/$$app/build"; \
	done

clean-compare:
	$(MAKE) -C compare clean BUILD_DIR="$(CURDIR)/compare/build"

help:
	@echo "make emu           Build the simulator: build/emu (default target)"
	@echo "  WITH_DIFFTEST=0/1 WITH_WAVE=0/1 JOBS=N"
	@echo "  Tools: SBT, VERILATOR; paths: BUILD_DIR, DEPS_DIR"
	@echo "make clean         Remove CPU build products and the repository dependency cache"
	@echo "make clean-all     Remove all build products and repository dependencies; retain comparison results"
	@echo "make clean-apps    Remove all apps' build products"
	@echo "make clean-compare Remove comparison build products"
	@echo
	@echo "Build an app:  make -C apps/hello"
	@echo "Run manually: ./build/emu -i apps/hello/build/hello.bin"
	@echo "Compare CPUs: cd compare && make help"
