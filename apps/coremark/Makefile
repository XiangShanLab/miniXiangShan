NAME := coremark
DEPS_DIR ?= $(abspath ../../.deps)
COREMARK_DIR := $(DEPS_DIR)/coremark
ITERATIONS ?= 10
TOTAL_DATA_SIZE ?= 2000
CPPFLAGS += -DITERATIONS=$(ITERATIONS) -DTOTAL_DATA_SIZE=$(TOTAL_DATA_SIZE) \
  -I$(COREMARK_DIR) -Isrc
SRCS := $(addprefix $(COREMARK_DIR)/,core_main.c core_list_join.c core_matrix.c \
  core_state.c core_util.c) src/core_portme.c
HEADERS := $(COREMARK_DIR)/coremark.h src/core_portme.h
BUILD_INPUTS := deps.lock checksums.sha256 report-results.patch fetch.sh
HOST_FLAGS := -O2 -DCOREMARK_HOST
EXTRA_OUTPUTS := $(CURDIR)/build/coremark-host
include ../common/app.mk

prepare:
	bash fetch.sh

$(BUILD_DIR)/coremark-host: $(BUILD_DIR)/.config
	$(HOST_CC) $(HOST_FLAGS) $(CPPFLAGS) $(SRCS) -o "$@.tmp"
	mv "$@.tmp" "$@"
