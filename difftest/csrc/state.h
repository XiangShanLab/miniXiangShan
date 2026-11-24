#pragma once

#include "Vcore_top.h"
#include <array>
#include <cstdint>

// NEMU riscv64-nutshell-ref ABI at difftest/deps.lock, with FPU/H/V disabled.
struct ArchState {
  std::array<uint64_t, 32> gpr{};
  uint64_t mode = 0;
  uint64_t mstatus = 0, sstatus = 0;
  uint64_t mepc = 0, sepc = 0, mtval = 0, stval = 0;
  uint64_t mtvec = 0, stvec = 0, mcause = 0, scause = 0;
  uint64_t satp = 0, mip = 0, mie = 0, mscratch = 0, sscratch = 0;
  uint64_t mideleg = 0, medeleg = 0, pc = 0;
};
static_assert(sizeof(ArchState) == 51 * sizeof(uint64_t));

struct Commit {
  bool valid, wen, exception, xret, interrupt, load, store;
  uint64_t pc, data, paddr, vaddr, store_data;
  uint32_t inst;
  unsigned dest, cause, interrupt_cause;
};

inline std::array<Commit, 3> commits(const Vcore_top& t) {
#define COMMIT(n) Commit{ \
  bool(t.difftestState_commit_##n##_valid), \
  bool(t.difftestState_commit_##n##_rfWen), \
  bool(t.difftestState_commit_##n##_excpFlush), \
  bool(t.difftestState_commit_##n##_xretFlush), \
  bool(t.difftestState_commit_##n##_isInterrupt), \
  bool(t.difftestState_commit_##n##_load_valid), \
  bool(t.difftestState_commit_##n##_store_valid), \
  t.difftestState_commit_##n##_pc, t.difftestState_commit_##n##_wdata, \
  t.difftestState_commit_##n##_load_valid ? t.difftestState_commit_##n##_load_paddr : t.difftestState_commit_##n##_store_paddr, \
  t.difftestState_commit_##n##_load_valid ? t.difftestState_commit_##n##_load_vaddr : t.difftestState_commit_##n##_store_vaddr, \
  t.difftestState_commit_##n##_store_data, t.difftestState_commit_##n##_instr, \
  t.difftestState_commit_##n##_wdest, t.difftestState_commit_##n##_cause, \
  t.difftestState_commit_##n##_interruptCause}
  return {COMMIT(0), COMMIT(1), COMMIT(2)};
#undef COMMIT
}

inline ArchState arch_state(const Vcore_top& t) {
  ArchState s;
#define REG(n) s.gpr[n] = t.difftestState_regs_##n
  REG(0); REG(1); REG(2); REG(3); REG(4); REG(5); REG(6); REG(7);
  REG(8); REG(9); REG(10); REG(11); REG(12); REG(13); REG(14); REG(15);
  REG(16); REG(17); REG(18); REG(19); REG(20); REG(21); REG(22); REG(23);
  REG(24); REG(25); REG(26); REG(27); REG(28); REG(29); REG(30); REG(31);
#undef REG
  s.mode = t.difftestState_csr_priv;
#define CSR(name) s.name = t.difftestState_csr_##name
  CSR(mstatus); CSR(sstatus); CSR(mepc); CSR(sepc); CSR(mtval); CSR(stval);
  CSR(mtvec); CSR(stvec); CSR(mcause); CSR(scause); CSR(satp); CSR(mip);
  CSR(mie); CSR(mscratch); CSR(sscratch); CSR(mideleg); CSR(medeleg);
#undef CSR
  return s;
}
