# Include from apps/<name>/Makefile after defining NAME, SRCS and HEADERS.
.DEFAULT_GOAL := image
.DELETE_ON_ERROR:
SHELL := /bin/bash
.SHELLFLAGS := -e -o pipefail -c
APP_DIR := $(CURDIR)
MINI_ROOT := $(abspath ../..)
COMMON_DIR := $(MINI_ROOT)/apps/common
BUILD_DIR := $(APP_DIR)/build
BINARY := $(BUILD_DIR)/$(NAME)
DEPS_DIR ?= $(MINI_ROOT)/.deps
export DEPS_DIR
CROSS_COMPILE ?= riscv64-unknown-elf-
RISCV_CC ?= $(CROSS_COMPILE)gcc
RISCV_OBJCOPY ?= $(CROSS_COMPILE)objcopy
RISCV_OBJDUMP ?= $(CROSS_COMPILE)objdump
HOST_CC ?= cc
CFLAGS ?= -O2
CPPFLAGS += -I$(COMMON_DIR)/include
APP_FLAGS := -march=rv64im_zicsr_zifencei -mabi=lp64 -ffreestanding \
  -fno-builtin -nostdlib -static -mcmodel=medany -msmall-data-limit=0
APP_SRCS := $(SRCS) $(COMMON_DIR)/start.S $(COMMON_DIR)/minilibc.c
BUILD_INPUTS += $(APP_DIR)/Makefile $(COMMON_DIR)/app.mk \
  $(COMMON_DIR)/link.ld $(COMMON_DIR)/bin2hex.py

.PHONY: image clean help FORCE prepare
prepare:

$(BUILD_DIR):
	mkdir -p "$@"

# Content fingerprints cover compiler/options, headers, source additions and
# deletions. Keep the stamp's mtime when the effective inputs are unchanged.
$(BUILD_DIR)/.config: FORCE | prepare $(BUILD_DIR)
	@key=$$(python3 "$(MINI_ROOT)/scripts/fingerprint.py" \
	  --value="$(RISCV_CC),$(HOST_CC),$(CFLAGS),$(CPPFLAGS),$(APP_FLAGS),$(HOST_FLAGS)" \
	  --value="$$($(RISCV_CC) --version | head -1)" \
	  --value="$$(if [[ -n '$(HOST_FLAGS)' ]]; then $(HOST_CC) --version | head -1; fi)" \
	  $(APP_SRCS) $(HEADERS) $(BUILD_INPUTS)); \
	if [[ ! -f "$@" || "$$(cat "$@")" != "$$key" ]]; then \
	  printf '%s\n' "$$key" > "$@"; \
	fi

$(BINARY).elf: $(BUILD_DIR)/.config
	$(RISCV_CC) $(APP_FLAGS) $(CFLAGS) $(CPPFLAGS) \
	  -T "$(COMMON_DIR)/link.ld" $(APP_SRCS) -o "$@.tmp"
	mv "$@.tmp" "$@"

$(BINARY).bin: $(BINARY).elf
	$(RISCV_OBJCOPY) -O binary "$<" "$@"

$(BINARY).hex: $(BINARY).bin $(COMMON_DIR)/bin2hex.py
	python3 "$(COMMON_DIR)/bin2hex.py" "$<" "$@"

$(BINARY).txt: $(BINARY).elf
	$(RISCV_OBJDUMP) -d -S "$<" > "$@"

image: $(BINARY).bin $(BINARY).hex $(BINARY).txt $(EXTRA_OUTPUTS)
	@printf '%s\n' 'APP=$(NAME)' 'RISCV_CC=$(RISCV_CC)' \
	  'FLAGS=$(APP_FLAGS) $(CFLAGS) $(CPPFLAGS)' \
	  "CONFIG=$$(cat '$(BUILD_DIR)/.config')" > "$(BUILD_DIR)/build-info.txt"
	@echo "Application: $(NAME) -> $(BUILD_DIR)"
	@echo "Run manually: $(MINI_ROOT)/build/emu -i $(BINARY).bin"
	@if [[ -f compare.json ]]; then \
	  echo "Compare CPUs: cd $(MINI_ROOT)/compare && make run APP=$(NAME)"; \
	fi

clean:
	rm -rf -- "$(BUILD_DIR)"

help:
	@echo "make          Build $(NAME).elf/.bin/.hex/.txt in this app's build/"
	@echo "make clean    Remove only this app's generated files"
	@echo "Toolchain: CROSS_COMPILE, RISCV_CC, RISCV_OBJCOPY, RISCV_OBJDUMP"
