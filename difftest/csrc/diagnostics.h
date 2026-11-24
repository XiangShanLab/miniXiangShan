#pragma once

#include "state.h"
#include <iomanip>
#include <ostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace diagnostics {

inline std::string hex(uint64_t value, unsigned width = 16) {
  std::ostringstream out;
  out << "0x" << std::hex << std::setfill('0') << std::setw(width) << value;
  return out.str();
}

inline std::string reg_name(unsigned index) {
  static constexpr const char* abi[] = {
    "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
    "s0/fp", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
    "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
    "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"
  };
  return "x" + std::to_string(index) + " (" + (index < 32 ? abi[index] : "invalid") + ")";
}

struct StateField {
  const char* name;
  uint64_t ArchState::*member;
};

inline constexpr StateField csr_fields[] = {
  {"privilege", &ArchState::mode}, {"mstatus", &ArchState::mstatus},
  {"sstatus", &ArchState::sstatus}, {"mepc", &ArchState::mepc},
  {"sepc", &ArchState::sepc}, {"mtval", &ArchState::mtval},
  {"stval", &ArchState::stval}, {"mtvec", &ArchState::mtvec},
  {"stvec", &ArchState::stvec}, {"mcause", &ArchState::mcause},
  {"scause", &ArchState::scause}, {"satp", &ArchState::satp},
  {"mip", &ArchState::mip}, {"mie", &ArchState::mie},
  {"mscratch", &ArchState::mscratch}, {"sscratch", &ArchState::sscratch},
  {"mideleg", &ArchState::mideleg}, {"medeleg", &ArchState::medeleg}
};

struct Difference {
  std::string name;
  uint64_t dut, ref;
};

class DiffFailure : public std::runtime_error {
public:
  std::string code;
  std::vector<Difference> differences;
  std::string hint;

  DiffFailure(std::string code, const std::string& reason,
              std::vector<Difference> differences = {}, std::string hint = {})
    : std::runtime_error(reason), code(std::move(code)),
      differences(std::move(differences)), hint(std::move(hint)) {}
};

inline void section(std::ostream& out, const std::string& title) {
  out << '\n' << std::string(97, '-') << "\n[" << title << "]\n";
}

inline void table_border(std::ostream& out) {
  out << "+--------------------+--------------------+--------------------+--------------------+-----------+\n";
}

inline void table_header(std::ostream& out) {
  table_border(out);
  out << "| Field / register   | DUT                | NEMU (REF)         | DUT XOR REF        | Compare   |\n";
  table_border(out);
}

inline void table_row(std::ostream& out, const std::string& name,
                      uint64_t dut, const uint64_t* ref) {
  out << "| " << std::left << std::setw(18) << name
      << " | " << std::setw(18) << hex(dut)
      << " | " << std::setw(18) << (ref ? hex(*ref) : "unavailable")
      << " | " << std::setw(18) << (ref ? hex(dut ^ *ref) : "unavailable")
      << " | " << std::setw(9) << (ref ? (dut == *ref ? "=" : "!=") : "N/A")
      << " |\n" << std::right;
}

inline void print_differences(std::ostream& out, const DiffFailure& failure) {
  if (!failure.differences.empty()) {
    table_header(out);
    for (const auto& field : failure.differences)
      table_row(out, field.name, field.dut, &field.ref);
    table_border(out);
  }
  if (!failure.hint.empty()) out << "  Inspect: " << failure.hint << '\n';
}

inline void print_gprs(std::ostream& out, const ArchState& dut, const ArchState* ref) {
  table_header(out);
  for (unsigned i = 0; i < dut.gpr.size(); ++i)
    table_row(out, reg_name(i), dut.gpr[i], ref ? &ref->gpr[i] : nullptr);
  table_border(out);
}

inline void print_csrs(std::ostream& out, const ArchState& dut, const ArchState* ref) {
  table_header(out);
  for (const auto& field : csr_fields)
    table_row(out, field.name, dut.*field.member, ref ? &(ref->*field.member) : nullptr);
  table_border(out);
  out << "  Privilege encoding: U=0, S=1, M=3.\n";
}

struct RetirementEvent {
  uint64_t cycle;
  unsigned lane;
  Commit commit;
  bool injected = false;
};

inline void print_event(std::ostream& out, const RetirementEvent& event, bool current) {
  const auto& c = event.commit;
  out << (current ? " => " : "    ") << "cycle=" << event.cycle << " lane=" << event.lane
      << " pc=" << hex(c.pc) << " inst=" << hex(c.inst, 8);
  if (c.wen) out << " wb=" << reg_name(c.dest) << ':' << hex(c.data);
  else out << " wb=-";
  if (c.load || c.store)
    out << (c.store ? " STORE" : " LOAD") << " va=" << hex(c.vaddr)
        << " pa=" << hex(c.paddr);
  if (c.store) out << " data=" << hex(c.store_data);
  if (c.exception) out << " EXCEPTION(cause=" << c.cause << ')';
  if (c.interrupt) out << " INTERRUPT(cause=" << c.interrupt_cause << ')';
  if (c.xret) out << " xRET";
  if (event.injected) out << " [writeback bit 0 injected into checker input]";
  out << '\n';
}

} // namespace diagnostics
