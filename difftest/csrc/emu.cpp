#include "diagnostics.h"
#include <verilated.h>
#if EMU_TRACE
#include <verilated_fst_c.h>
#endif
#if WITH_DIFFTEST
#include <dlfcn.h>
#endif

#include <algorithm>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <memory>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using diagnostics::DiffFailure;
using diagnostics::hex;
using diagnostics::reg_name;
constexpr uint64_t base = 0x80000000;
volatile std::sig_atomic_t interrupted = 0;
void on_signal(int) { interrupted = 1; }
uint64_t number(const std::string& s) {
  size_t end = 0;
  if (s.empty() || s[0] == '-') throw std::runtime_error("invalid number: " + s);
  uint64_t n = std::stoull(s, &end, 0);
  if (end != s.size()) throw std::runtime_error("invalid number: " + s);
  return n;
}
struct Options {
  std::string image, reference, wave, trace;
  uint64_t max_cycles = 20000000, max_instr = 0, stuck = 100000;
  uint64_t wave_begin = 0, wave_end = UINT64_MAX;
  unsigned ram_mb = 16, seed = 1, latency = 1;
  bool diff = WITH_DIFFTEST, check_csr = true, ebreak = true;
  bool strict_axi_reads = false;
  uint64_t inject_at = 0;
};
void usage() {
  std::cout <<
    "miniXiangShan emu (RV64IM_Zicsr_Zifencei, M/S/U, Bare/Sv39)\n"
    "Usage: emu -i IMAGE.bin [options]\n"
    "  -i, --image FILE          Raw binary loaded at 0x80000000 (not ELF/hex)\n"
    "  --diff REF.so            Enable NEMU difftest with this reference\n"
    "  --no-diff                Run without loading a reference\n"
    "  --[no-]check-csr          Enable/disable CSR comparisons (default on)\n"
    "  -C, --max-cycles N        Cycle limit; timeout exits with status 2\n"
    "  -I, --max-instr N         Stop successfully after N retirements (0=off)\n"
    "  --stuck-limit N          Cycles without retirement (default 100000)\n"
    "  --ram-size N             DRAM size in MiB (default 16, max 2048)\n"
    "  --memory-latency N        Read latency in cycles (default 1)\n"
    "  --strict-axi-reads        Stop on unmapped AXI reads, including speculation\n"
    "  --seed N                 Verilator random initialization seed\n"
    "  --wave FILE.fst          Record waveform (requires WITH_WAVE=1 build)\n"
    "  --wave-begin N           First waveform cycle (default 0)\n"
    "  --wave-end N             Last waveform cycle\n"
    "  --trace FILE             Ordered retirement log\n"
    "  --trap ebreak|none       End on EBREAK/a0, or test real breakpoint traps\n"
    "  --inject-error N         Flip one writeback at retirement N (checker test)\n"
    "  -h, --help               Print this help\n"
    "  --version                Print compile-time configuration\n"
    "Exit: 0 success, 1 mismatch/bad trap/error, 2 timeout, 130 interrupted.\n";
}
Options parse(int argc, char** argv) {
  Options o;
  auto take = [&](int& i, const std::string& inline_arg) {
    if (!inline_arg.empty()) return inline_arg;
    if (++i >= argc) throw std::runtime_error("missing option argument");
    return std::string(argv[i]);
  };
  for (int i = 1; i < argc; ++i) {
    std::string arg = argv[i], val;
    auto eq = arg.find('=');
    if (eq != std::string::npos) { val = arg.substr(eq + 1); arg.resize(eq); }
    if (arg == "-h" || arg == "--help") { usage(); std::exit(0); }
    else if (arg == "--version") {
    std::cout << "miniXiangShan emu WITH_DIFFTEST=" << WITH_DIFFTEST
              << " WITH_WAVE=" << EMU_TRACE << '\n';
      std::exit(0);
    }
    else if (arg == "-i" || arg == "--image") o.image = take(i, val);
    else if (arg == "--diff") { o.reference = take(i, val); o.diff = true; }
    else if (arg == "--no-diff") o.diff = false;
    else if (arg == "--check-csr") o.check_csr = true;
    else if (arg == "--no-check-csr") o.check_csr = false;
    else if (arg == "--strict-axi-reads") o.strict_axi_reads = true;
    else if (arg == "--wave") o.wave = take(i, val);
    else if (arg == "--trace") o.trace = take(i, val);
    else if (arg == "--wave-begin") o.wave_begin = number(take(i, val));
    else if (arg == "--wave-end") o.wave_end = number(take(i, val));
    else if (arg == "-C" || arg == "--max-cycles") o.max_cycles = number(take(i, val));
    else if (arg == "-I" || arg == "--max-instr") o.max_instr = number(take(i, val));
    else if (arg == "--stuck-limit") o.stuck = number(take(i, val));
    else if (arg == "--ram-size") {
      auto n = number(take(i, val));
      if (n == 0 || n > 2048) throw std::runtime_error("--ram-size must be 1..2048");
      o.ram_mb = unsigned(n);
    }
    else if (arg == "--seed" || arg == "--memory-latency") {
      auto n = number(take(i, val));
      if (n > UINT32_MAX || (arg == "--memory-latency" && (n == 0 || n > 10000)))
        throw std::runtime_error("seed/latency out of range");
      if (arg == "--seed") o.seed = unsigned(n); else o.latency = unsigned(n);
    }
    else if (arg == "--inject-error") o.inject_at = number(take(i, val));
    else if (arg == "--trap") {
      auto s = take(i, val);
      if (s != "ebreak" && s != "none") throw std::runtime_error("invalid trap mode");
      o.ebreak = s == "ebreak";
    }
    else throw std::runtime_error("unknown option: " + arg);
  }
  if (o.image.empty()) throw std::runtime_error("missing -i IMAGE.bin");
  if (!o.max_cycles || !o.stuck) throw std::runtime_error("cycle/stuck limits must be positive");
  if (o.wave_begin > o.wave_end) throw std::runtime_error("invalid waveform interval");
  if (!EMU_TRACE && !o.wave.empty()) throw std::runtime_error("rebuild with WITH_WAVE=1 for --wave");
  if (!WITH_DIFFTEST && o.diff) throw std::runtime_error("rebuild with WITH_DIFFTEST=1 for --diff");
  if (o.inject_at && !o.diff) throw std::runtime_error("--inject-error requires difftest");
  if (o.reference.empty()) {
    auto build = std::filesystem::canonical("/proc/self/exe").parent_path();
    o.reference = (build / "ref/riscv64-nemu-interpreter-so").string();
  }
  return o;
}

class Memory {
  struct Burst {
    bool active = false;
    uint64_t addr = 0;
    unsigned id = 0, len = 0, size = 0, kind = 0, beat = 0, wait = 0;
    uint64_t address() const {
      const uint64_t offset = uint64_t(beat) << size;
      if (kind == 0) return addr;
      if (kind == 2) {
        const uint64_t mask = (uint64_t(len + 1) << size) - 1;
        return (addr & ~mask) | ((addr + offset) & mask);
      }
      return addr + offset;
    }
  } rd, wr;
  bool response = false;
  bool strict_reads;
  unsigned response_id = 0, latency;
  static bool contains(uint64_t start, uint64_t length, uint64_t addr, uint64_t size) {
    return size && size <= length && addr >= start && addr - start <= length - size;
  }
  uint64_t read_word(uint64_t addr) const {
    if (is_mmio(addr)) return 0;
    addr &= ~uint64_t(7);
    if (!in_dram(addr, 8)) {
      if (strict_reads)
        throw std::runtime_error("AXI read outside DRAM: " + hex(addr) +
          " (--strict-axi-reads stops even for instructions that may later be squashed)");
      // An AXI request has no ROB identity. Complete speculative reads so an
      // eventual redirect can drain them. Successful retirements are checked
      // separately; this placeholder is never an architectural memory mapping.
      return 0;
    }
    uint64_t data = 0;
    for (unsigned i = 0; i < 8; ++i) data |= uint64_t(bytes[addr - base + i]) << (i * 8);
    return data;
  }
  void write_word(uint64_t addr, uint64_t data, unsigned mask) {
    if (is_mmio(addr)) {
      if (addr == 0x4000f038 && (mask & 1)) {
        std::putchar(int(data & 0xff)); std::fflush(stdout);
      }
      if (addr == 0x4000f000 && (mask & 15)) signature = uint32_t(data);
      if (addr == 0x4000f030 && (mask & 15)) done = true;
      return;
    }
    addr &= ~uint64_t(7);
    if (!in_dram(addr, 8))
      throw std::runtime_error("AXI write outside DRAM: " + hex(addr));
    for (unsigned i = 0; i < 8; ++i)
      if (mask & (1u << i)) bytes[addr - base + i] = uint8_t(data >> (i * 8));
  }
  static Burst burst(uint64_t addr, unsigned id, unsigned len, unsigned size,
                     unsigned kind, unsigned latency) {
    if (size > 3 || kind > 2 || (kind == 2 && len != 1 && len != 3 && len != 7 && len != 15))
      throw std::runtime_error("unsupported AXI burst");
    return {true, addr, id, len, size, kind, 0, latency - 1};
  }
public:
  std::vector<uint8_t> bytes;
  std::vector<uint8_t> image;
  bool done = false;
  uint32_t signature = 0;
  static bool is_mmio(uint64_t addr, uint64_t size = 1) {
    return contains(0x4000f000, 0x100, addr, size);
  }
  bool in_dram(uint64_t addr, uint64_t size) const {
    return contains(base, bytes.size(), addr, size);
  }
  void check_commit(const Commit& c) const {
    if (c.exception || (!c.load && !c.store)) return;
    const unsigned size = 1u << ((c.inst >> 12) & 3);
    if (!in_dram(c.paddr, size) && !is_mmio(c.paddr, size))
      throw std::runtime_error(std::string("UNMAPPED_COMMITTED_ACCESS: ") +
        (c.store ? "store" : "load") + " retired outside DRAM/MMIO: PC=" + hex(c.pc) +
        ", VA=" + hex(c.vaddr) + ", PA=" + hex(c.paddr) + ", bytes=" + std::to_string(size) +
        ". This instruction was not squashed; placeholder read data cannot be committed.");
  }
  void check_fetch(const Commit& c, const ArchState& before) const {
    // Commit PC is virtual under Sv39; the ABI does not expose fetch PA.
    const bool translated = before.mode != 3 && (before.satp >> 60) == 8;
    if (!translated && !in_dram(c.pc, 4))
      throw std::runtime_error("UNMAPPED_COMMITTED_FETCH: instruction event outside DRAM: PC=" +
        hex(c.pc) + ". Check branch/jump targets; an unmapped speculative fetch reached retirement.");
  }
  explicit Memory(const Options& o) : strict_reads(o.strict_axi_reads), latency(o.latency),
                                    bytes(size_t(o.ram_mb) << 20) {
    std::ifstream f(o.image, std::ios::binary | std::ios::ate);
    if (!f) throw std::runtime_error("cannot open image: " + o.image);
    auto size = f.tellg();
    if (size <= 0 || uint64_t(size) > bytes.size()) throw std::runtime_error("empty/oversized image");
    image.resize(size_t(size));
    f.seekg(0); f.read(reinterpret_cast<char*>(image.data()), size);
    if (!f) throw std::runtime_error("failed to read image");
    if (image.size() >= 4 && image[0] == 0x7f && image[1] == 'E' && image[2] == 'L' && image[3] == 'F')
      throw std::runtime_error("ELF is not supported; use riscv64-unknown-elf-objcopy -O binary");
    std::copy(image.begin(), image.end(), bytes.begin());
  }
  void drive(Vcore_top& t) const {
    t.arready = !rd.active;
    t.rvalid = rd.active && !rd.wait;
    t.rid = rd.id;
    t.rdata = t.rvalid ? read_word(rd.address()) : 0;
    t.rlast = rd.active && rd.beat == rd.len;
    t.rresp = 0;
    t.ruser = 0;
    t.awready = !wr.active && !response;
    t.wready = wr.active && !response;
    t.bvalid = response;
    t.bid = response_id;
    t.bresp = 0;
    t.buser = 0;
  }
  void accept(const Vcore_top& t) {
    if (rd.active && rd.wait) --rd.wait;
    if (t.rvalid && t.rready) {
      if (rd.beat == rd.len) rd.active = false; else ++rd.beat;
    }
    if (t.arvalid && t.arready)
      rd = burst(t.araddr, t.arid, t.arlen, t.arsize, t.arburst, latency);
    if (t.bvalid && t.bready) response = false;
    if (t.awvalid && t.awready)
      wr = burst(t.awaddr, t.awid, t.awlen, t.awsize, t.awburst, 1);
    if (t.wvalid && t.wready) {
      if (bool(t.wlast) != (wr.beat == wr.len))
        throw std::runtime_error("invalid AXI4 write last");
      write_word(wr.address(), t.wdata, t.wstrb);
      if (t.wlast) { wr.active = false; response = true; response_id = wr.id; }
      else ++wr.beat;
    }
  }
};

#if WITH_DIFFTEST
class Reference {
  void* handle = nullptr;
  void (*regcpy)(void*, bool) = nullptr;
  void (*memcpy_ref)(uint64_t, void*, size_t, bool) = nullptr;
  void (*exec)(uint64_t) = nullptr;
  void (*raise_intr)(uint64_t) = nullptr;
  void (*csrcpy)(void*, bool) = nullptr;
  int (*store_commit)(uint64_t*, uint64_t*, uint8_t*) = nullptr;
  template<class T> T symbol(const char* name) {
    auto p = dlsym(handle, name);
    if (!p) throw std::runtime_error(std::string("NEMU reference is missing required symbol '") +
      name + "'. Use an ABI-compatible reference or rebuild it with make emu.");
    return reinterpret_cast<T>(p);
  }
  static bool mmio(uint64_t addr) { return Memory::is_mmio(addr); }
public:
  uint64_t skipped = 0, stores = 0;
  ArchState state{};
  const char* state_position = "initial state after reset";
  Reference(const Options& o, Memory& mem, ArchState initial, uint64_t misa) {
    handle = dlopen(o.reference.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) throw std::runtime_error("cannot load NEMU reference '" + o.reference +
      "': " + dlerror() + "\nBuild the default reference with make emu, or select a library with --diff PATH.");
    auto size = symbol<const unsigned*>("DIFFTEST_REG_SIZE");
    if (*size != sizeof(ArchState))
      throw std::runtime_error("NEMU state ABI mismatch in '" + o.reference + "': emu expects " +
        std::to_string(sizeof(ArchState)) + " bytes, reference exports " + std::to_string(*size) +
        " bytes. Rebuild the pinned integer-only reference with make emu.");
    symbol<void(*)(size_t)>("difftest_set_ramsize")(mem.bytes.size());
    symbol<void(*)(unsigned)>("difftest_init_v2")(sizeof(ArchState));
    regcpy = symbol<decltype(regcpy)>("difftest_regcpy");
    memcpy_ref = symbol<decltype(memcpy_ref)>("difftest_memcpy");
    exec = symbol<decltype(exec)>("difftest_exec");
    raise_intr = symbol<decltype(raise_intr)>("difftest_raise_intr");
    csrcpy = symbol<decltype(csrcpy)>("difftest_csrcpy");
    store_commit = symbol<decltype(store_commit)>("difftest_store_commit");
    memcpy_ref(base, mem.image.data(), mem.image.size(), true);
    std::array<uint64_t, 4096> csrs{};
    csrcpy(csrs.data(), false);
    csrs[0x301] = misa;
    csrcpy(csrs.data(), true);
    initial.pc = base;
    state = initial;
    regcpy(&state, true);
  }
  ~Reference() { if (handle) dlclose(handle); }
  void step(const Commit& c) {
    state_position = "before the current retirement event (NEMU has not executed it)";
    if (state.pc != c.pc)
      throw DiffFailure("PC_MISMATCH", "Retired instruction PC differs from NEMU's next instruction PC.",
        {{"instruction PC", c.pc, state.pc}},
        "branch/jump targets, exception/xRET redirects, and retirement ordering.");
    if (!c.exception) {
      const bool load = (c.inst & 0x7f) == 0x03;
      const bool store = (c.inst & 0x7f) == 0x23;
      if (load != c.load || store != c.store)
        throw DiffFailure("MEMORY_TYPE", "Committed load/store flags disagree with the instruction opcode.",
          {{"load flag", c.load, load}, {"store flag", c.store, store}},
          "decode and alignment of the instruction with its retirement metadata; REF here is opcode decoding.");
      if (load || store) {
        int64_t imm = int32_t(c.inst) >> 20;
        if (store) imm = int64_t(int32_t(c.inst) >> 25) * 32 + ((c.inst >> 7) & 31);
        uint64_t addr = state.gpr[(c.inst >> 15) & 31] + uint64_t(imm);
        if (addr != c.vaddr)
          throw DiffFailure("VIRTUAL_ADDRESS", "Committed effective virtual address differs from rs1 + immediate.",
            {{"virtual address", c.vaddr, addr}},
            "address generation and source operand forwarding. NEMU rs1=" + reg_name((c.inst >> 15) & 31) +
            ", value=" + hex(state.gpr[(c.inst >> 15) & 31]) + ", signed immediate=" + std::to_string(imm) + '.');
        auto privilege = state.mode;
        if (privilege == 3 && (state.mstatus & (uint64_t(1) << 17)))
          privilege = (state.mstatus >> 11) & 3;
        const bool translated = privilege != 3 && (state.satp >> 60) == 8;
        if (!translated && c.paddr != addr)
          throw DiffFailure("PHYSICAL_ADDRESS", "An untranslated access must use its effective address as physical address.",
            {{"physical address", c.paddr, addr}},
            "Bare/M-mode address handling, MPRV/MPP, and memory retirement metadata.");
        // Do not let a faulty translation into MMIO silently bypass NEMU.
        if (translated && mmio(c.paddr))
          throw DiffFailure("UNSUPPORTED_TRANSLATED_MMIO",
            "The access is translated into MMIO, which this platform cannot check.", {},
            "Sv39 page mapping: VA=" + hex(c.vaddr) + ", PA=" + hex(c.paddr) +
            ", satp=" + hex(state.satp) + ". MMIO synchronization is supported only for untranslated accesses.");
      }
    }
    const unsigned csr = c.inst >> 20;
    const unsigned funct3 = (c.inst >> 12) & 7;
    const bool counter = (c.inst & 0x7f) == 0x73 &&
      (funct3 == 2 || funct3 == 3 || funct3 == 6 || funct3 == 7) &&
      ((c.inst >> 15) & 31) == 0 &&
      (csr == 0xc00 || csr == 0xc01 || csr == 0xc02 || csr == 0xb00 || csr == 0xb02);
    if (!c.exception && (((c.load || c.store) && mmio(c.paddr)) || counter)) {
      // Only a deterministic instruction's external result is synchronized.
      // Preserve all other registers/CSRs, including earlier writes in this group.
      if (c.wen && c.dest) state.gpr[c.dest] = c.data;
      state.pc = c.pc + 4;
      regcpy(&state, true);
      ++skipped;
      state_position = "after the current retirement event (MMIO/counter result synchronized)";
      return;
    }
    if (c.interrupt) raise_intr((uint64_t(1) << 63) | c.interrupt_cause);
    else exec(1);
    regcpy(&state, false);
    state_position = "after the current retirement event (later lanes have not executed in NEMU)";
    if (!c.exception && c.wen && c.dest && state.gpr[c.dest] != c.data)
      throw DiffFailure("GPR_WRITEBACK", "Retired GPR writeback differs from NEMU's instruction result.",
        {{reg_name(c.dest), c.data, state.gpr[c.dest]}},
        "execution result, operand forwarding, destination register, and commit writeback alignment.");
    if (!c.exception && c.store) {
      if ((c.inst & 0x7f) != 0x23)
        throw DiffFailure("UNSUPPORTED_STORE", "Store checker supports RV64I sb/sh/sw/sd only.");
      unsigned size = 1u << ((c.inst >> 12) & 7), offset = c.paddr & 7;
      if (size > 8 || offset + size > 8)
        throw DiffFailure("STORE_FORMAT", "Committed store has an invalid width or crosses an 8-byte word.", {},
          "store decode/alignment: bytes=" + std::to_string(size) + ", byte offset=" +
          std::to_string(offset) + ", PA=" + hex(c.paddr) + '.');
      uint64_t addr = c.paddr & ~uint64_t(7);
      uint64_t mask_data = size == 8 ? UINT64_MAX : (uint64_t(1) << (size * 8)) - 1;
      uint64_t data = (c.store_data & mask_data) << (offset * 8);
      uint8_t mask = uint8_t(((1u << size) - 1) << offset);
      const auto dut_addr = addr, dut_data = data;
      const auto dut_mask = mask;
      if (store_commit(&addr, &data, &mask)) {
        // NEMU overwrites this tuple on a mismatch, but leaves it unchanged
        // for an empty/overflowed queue. Do not invent a reference value then.
        if (addr == dut_addr && data == dut_data && mask == dut_mask)
          throw DiffFailure("STORE_QUEUE", "NEMU rejected the store without returning a different reference tuple.", {},
            "preceding NEMU queue diagnostics (empty/overflow). DUT aligned PA=" + hex(dut_addr) +
            ", aligned data=" + hex(dut_data) + ", byte mask=" + hex(dut_mask, 2) + '.');
        throw DiffFailure("STORE_MISMATCH", "Retired store differs from NEMU's queued store.",
          {{"aligned address", dut_addr, addr}, {"aligned store data", dut_data, data},
           {"store byte mask", dut_mask, mask}},
          "store address translation, rs2 forwarding, byte-lane alignment, and store width.");
      }
      ++stores;
    }
  }
  void compare(const ArchState& dut, bool check_csr) {
    state_position = "after all processed events in the current retirement group";
    std::vector<diagnostics::Difference> differences;
    for (unsigned i = 0; i < 32; ++i)
      if (dut.gpr[i] != state.gpr[i])
        differences.push_back({reg_name(i), dut.gpr[i], state.gpr[i]});
    if (check_csr)
      for (const auto& field : diagnostics::csr_fields)
        if (dut.*field.member != state.*field.member)
          differences.push_back({field.name, dut.*field.member, state.*field.member});
    if (!differences.empty()) {
      const auto count = differences.size();
      throw DiffFailure("ARCHITECTURAL_STATE", "Architectural state differs after the retirement group (" +
        std::to_string(count) + " differing fields).", std::move(differences),
        "architectural RAT/PRF updates, multiple writes to the same GPR, CSR/trap updates, and snapshot timing.");
    }
  }
};
#endif

int run(const Options& o) {
  VerilatedContext context;
  context.randSeed(o.seed);
  context.randReset(2);
  context.traceEverOn(!o.wave.empty());
  Vcore_top top(&context);
  Memory memory(o);
  std::ofstream retire_log;
  if (!o.trace.empty()) {
    retire_log.open(o.trace);
    if (!retire_log) throw std::runtime_error("cannot open retirement log");
    retire_log << "# cycle lane pc instruction exception\n";
  }
#if EMU_TRACE
  std::unique_ptr<VerilatedFstC> wave;
  if (!o.wave.empty()) {
    wave = std::make_unique<VerilatedFstC>();
    top.trace(wave.get(), 99);
    wave->open(o.wave.c_str());
    if (!wave->isOpen()) throw std::runtime_error("cannot open waveform");
  }
#endif
  uint64_t cycles = 0, retired = 0, trap_events = 0, last_progress = 0;
  bool injected = false;
  std::deque<diagnostics::RetirementEvent> history;
  std::array<Commit, 3> current_group{};
  int active_lane = -1;
  const char* phase = "initialization";
  auto eval = [&] {
    top.eval();
#if EMU_TRACE
    if (wave && cycles >= o.wave_begin && cycles <= o.wave_end) wave->dump(context.time());
#endif
    context.timeInc(1);
  };
  top.aresetn = 0;
  top.intrpt = 0;
  top.break_point = 0; top.infor_flag = 0; top.reg_num = 0;
  for (unsigned i = 0; i < 8; ++i) {
    top.aclk = 0; memory.drive(top); eval();
    top.aclk = 1; eval();
  }
  ArchState last_group_state = arch_state(top);
#if WITH_DIFFTEST
  std::unique_ptr<Reference> reference;
  if (o.diff) reference = std::make_unique<Reference>(
    o, memory, arch_state(top), top.difftestState_csr_misa);
#endif
  std::cout << "EMU image=" << o.image << " difftest=" << (o.diff ? "on" : "off")
    << " seed=" << o.seed << " memory_latency=" << o.latency << '\n';
  top.aresetn = 1;
  auto finish = [&](int status, const char* reason) {
    top.final();
#if EMU_TRACE
    if (wave) wave->close();
#endif
    std::cout << "EMU_RESULT status=" << (status == 0 ? "success" : reason)
      << " reason=" << reason << " cycles=" << cycles
      << " retired=" << retired << " exceptions=" << trap_events
      << " ipc=" << (cycles ? double(retired) / cycles : 0)
      << " difftest=" << (o.diff ? "on" : "off");
#if WITH_DIFFTEST
    if (reference) std::cout << " skipped=" << reference->skipped
      << " stores_checked=" << reference->stores;
#endif
    std::cout << '\n' << std::flush;
    return status;
  };
  auto report_diagnostic = [&](const char* title, const std::string& code,
                               const std::string& reason, const DiffFailure* failure = nullptr) {
    std::cout.flush();
    auto& out = std::cerr;
    const auto dut = arch_state(top);
    const ArchState* ref = nullptr;
#if WITH_DIFFTEST
    if (reference) ref = &reference->state;
#endif
    out << '\n' << std::string(97, '=')
        << "\n  miniXiangShan | " << title
        << "\n" << std::string(97, '=') << '\n';
    out << "  Code    : " << code
        << "\n  Reason  : " << reason
        << "\n  Cycle   : " << cycles << "    Retired: " << retired
        << "    Exceptions: " << trap_events
        << "\n  Phase   : " << phase << "    Active lane: ";
    if (active_lane >= 0) out << active_lane << '\n'; else out << "none\n";
    out << "  Image   : " << o.image
        << "\n  NEMU    : " << (o.diff ? o.reference : "disabled")
        << "\n  Config  : seed=" << o.seed << " ram_mib=" << o.ram_mb
        << " memory_latency=" << o.latency << " csr_check=" << (o.check_csr ? "on" : "off") << '\n';
#if WITH_DIFFTEST
    if (reference) out << "  Checked : stores=" << reference->stores
                       << " synchronized_mmio_or_counter=" << reference->skipped << '\n';
#endif
    if (failure) {
      diagnostics::section(out, "DETECTED DIFFERENCES");
      diagnostics::print_differences(out, *failure);
    }
    diagnostics::section(out, "RETIREMENT PROGRESS");
    out << "  Cycles without a valid retirement event: " << cycles - last_progress
        << "\n  Limits: --stuck-limit=" << o.stuck << "  --max-cycles=" << o.max_cycles << '\n';
    if (history.empty()) out << "  Last retirement: none since reset was released.\n";
    else {
      out << "  Last retirement event (cycle " << last_progress << "):\n";
      diagnostics::print_event(out, history.back(), true);
    }
    if (code == "NO_COMMIT_TIMEOUT") {
      out << "  Inspect: pipeline/ROB stalls, cache/TLB misses, and pending AXI handshakes.\n";
      if (history.empty()) out << "  Also inspect reset release, reset PC, and the first instruction fetch.\n";
      out << "  If a long wait is expected, increase --stuck-limit N and inspect the waveform.\n";
    } else if (code == "MAX_CYCLES") {
      out << "  Inspect: loop termination and the application's EBREAK/MMIO completion path.\n"
          << "  For a longer workload, increase --max-cycles N.\n";
    }
    diagnostics::section(out, "CURRENT RETIREMENT GROUP | => active event, lanes in program order");
    bool has_event = false;
    for (unsigned lane = 0; lane < current_group.size(); ++lane) {
      if (!current_group[lane].valid) continue;
      has_event = true;
      diagnostics::print_event(out, {cycles, lane, current_group[lane]}, int(lane) == active_lane);
    }
    if (!has_event) out << "  No retirement event captured in this cycle.\n";
    if (!history.empty() && history.back().injected && history.back().cycle == cycles)
      out << "  Error injection: lane " << history.back().lane
          << " checker writeback=" << hex(history.back().commit.data)
          << "; the group above retains the original RTL output.\n";
    diagnostics::section(out, "SNAPSHOT ALIGNMENT");
    out << "  DUT : architectural RAT/PRF and CSR ports for the whole current retirement group.\n";
#if WITH_DIFFTEST
    if (reference) out << "  NEMU: " << reference->state_position << ".\n"
                       << "  NEMU next PC: " << hex(ref->pc) << '\n';
#endif
    if (!ref) out << "  NEMU: unavailable (difftest disabled).\n";
    out << "  DUT has no architectural next-PC port; use commit PCs above for instruction alignment.\n";
    if (failure)
      out << "  Mid-group failures can leave DUT and NEMU at different positions; table != marks\n"
          << "  snapshot differences. The detected differences section identifies the failed check.\n";
    if (!o.check_csr)
      out << "  CSR checking is disabled; CSR values below are diagnostic only.\n";
    diagnostics::section(out, "GPR SNAPSHOTS | all 32 integer registers, != marks different values");
    diagnostics::print_gprs(out, dut, ref);
    diagnostics::section(out, "CSR / PRIVILEGE SNAPSHOTS | fields exposed by the reference ABI");
    diagnostics::print_csrs(out, dut, ref);
    diagnostics::section(out, "AXI BUS SNAPSHOT | valid/ready levels at the stop point");
    out << "  AR: " << unsigned(top.arvalid) << '/' << unsigned(top.arready)
        << "  address=" << hex(top.araddr)
        << "\n  R : " << unsigned(top.rvalid) << '/' << unsigned(top.rready)
        << "  last=" << unsigned(top.rlast)
        << "\n  AW: " << unsigned(top.awvalid) << '/' << unsigned(top.awready)
        << "  address=" << hex(top.awaddr)
        << "\n  W : " << unsigned(top.wvalid) << '/' << unsigned(top.wready)
        << "  last=" << unsigned(top.wlast)
        << "\n  B : " << unsigned(top.bvalid) << '/' << unsigned(top.bready) << '\n';
    diagnostics::section(out, "RECENT RETIREMENT EVENTS | oldest first, up to 32, => latest processed");
    for (size_t i = 0; i < history.size(); ++i)
      diagnostics::print_event(out, history[i], i + 1 == history.size());
    if (history.empty()) out << "  No retirement events processed.\n";
    diagnostics::section(out, "DEBUG ARTIFACTS");
    if (!o.trace.empty()) out << "  Retirement log: " << o.trace << '\n';
    else out << "  Retirement log: add --trace FILE to record the full retirement history.\n";
    if (!o.wave.empty()) out << "  Waveform: " << o.wave << " (requested cycles "
                              << o.wave_begin << ".." << o.wave_end << ")\n";
    else out << "  Waveform: add --wave FILE.fst (requires a WITH_WAVE=1 build).\n";
    out << std::string(97, '=') << '\n';
  };
  try {
    while (cycles < o.max_cycles && !context.gotFinish() && !interrupted) {
      current_group = {};
      active_lane = -1;
      phase = "RTL / AXI cycle";
#if WITH_DIFFTEST
      if (reference) reference->state_position = "after the last successfully checked retirement group";
#endif
      top.aclk = 0; memory.drive(top); eval();
      memory.accept(top);
      top.aclk = 1; eval();
      ++cycles;
      current_group = commits(top);
      const auto group_state = arch_state(top);
      bool any = false;
      int trap_code = -1;
      for (unsigned lane = 0; lane < current_group.size(); ++lane) {
        auto c = current_group[lane];
        if (!c.valid) continue;
        active_lane = int(lane);
        phase = "per-instruction retirement check";
        any = true;
        last_progress = cycles;
        std::ostringstream line;
        line << cycles << ' ' << lane << ' ' << hex(c.pc) << ' '
          << hex(c.inst) << " exception=" << c.exception;
        history.push_back({cycles, lane, c});
        if (history.size() > 32) history.pop_front();
        if (retire_log) retire_log << line.str() << '\n';
        // Bus reads can precede a redirect. Check successful accesses here,
        // before MMIO synchronization or executing the instruction in NEMU.
        memory.check_commit(c);
        const ArchState* fetch_context = nullptr;
#if WITH_DIFFTEST
        if (reference) fetch_context = &reference->state;
#endif
        // Without NEMU there is no per-instruction CSR snapshot. Only
        // assert identity-mapped fetches if the group did not change mode/satp.
        if (!fetch_context && last_group_state.mode == group_state.mode &&
            last_group_state.satp == group_state.satp)
          fetch_context = &last_group_state;
        if (fetch_context) memory.check_fetch(c, *fetch_context);
        // EBREAK termination is a harness convention, not a normal retirement.
        if (o.ebreak && c.inst == 0x00100073) {
#if WITH_DIFFTEST
          if (reference) {
            reference->state_position = "before the terminal EBREAK (harness termination; not executed in NEMU)";
            if (reference->state.pc != c.pc)
              throw DiffFailure("TERMINAL_PC", "Terminal EBREAK PC differs from NEMU's next instruction PC.",
                {{"terminal PC", c.pc, reference->state.pc}},
                "control flow leading to program termination and retirement ordering.");
          }
#endif
          trap_code = group_state.gpr[10] != 0 ? 1 : 0;
          break;
        }
        if (c.exception) ++trap_events; else ++retired;
        if (!c.exception && o.inject_at == retired && c.wen && c.dest) {
          c.data ^= 1; injected = true;
          history.back().commit = c;
          history.back().injected = true;
        } else if (!c.exception && o.inject_at && o.inject_at == retired) {
          throw std::runtime_error("injection retirement must write a nonzero GPR");
        }
#if WITH_DIFFTEST
        if (reference) reference->step(c);
#endif
      }
      active_lane = -1;
      phase = "architectural state check after retirement group";
#if WITH_DIFFTEST
      if (reference && any) reference->compare(group_state, o.check_csr && trap_code < 0);
#endif
      last_group_state = group_state;
      phase = "termination / progress check";
      if (trap_code >= 0) {
        if (o.inject_at && !injected) throw std::runtime_error("injection point not reached");
        std::cout << (trap_code ? "HIT BAD TRAP" : "HIT GOOD TRAP")
          << " code=" << trap_code << '\n';
        return finish(trap_code ? 1 : 0, trap_code ? "bad-trap" : "good-trap");
      }
      if (memory.done) {
        if (memory.signature != 0x434d524b)
          throw std::runtime_error("invalid performance completion signature");
        if (o.inject_at && !injected) throw std::runtime_error("injection point not reached");
        return finish(0, "mmio-exit");
      }
      if (o.max_instr && retired >= o.max_instr) return finish(0, "instruction-limit");
      if (cycles - last_progress >= o.stuck) {
        phase = "retirement progress watchdog";
        report_diagnostic("SIMULATION STALLED", "NO_COMMIT_TIMEOUT",
          "No valid retirement event for " + std::to_string(cycles - last_progress) +
          " cycles; reached --stuck-limit=" + std::to_string(o.stuck) + '.');
        return finish(2, "stuck");
      }
    }
  } catch (const std::exception& e) {
    const auto* failure = dynamic_cast<const DiffFailure*>(&e);
    report_diagnostic(failure ? "DIFFTEST FAILURE" : "SIMULATION ERROR",
      failure ? failure->code : "SIMULATION_ERROR", e.what(), failure);
    return finish(1, "error");
  }
  if (interrupted) return finish(130, "interrupted");
  if (context.gotFinish()) {
    phase = "RTL finish";
    report_diagnostic("SIMULATION STOPPED", "RTL_FINISH",
      "RTL requested $finish before the application reported completion.");
  } else {
    phase = "total cycle watchdog";
    report_diagnostic("SIMULATION TIMEOUT", "MAX_CYCLES",
      "Reached --max-cycles=" + std::to_string(o.max_cycles) +
      " before the application reported completion.");
  }
  return finish(2, "timeout");
}
} // namespace

int main(int argc, char** argv) {
  std::signal(SIGINT, on_signal);
  std::signal(SIGTERM, on_signal);
  try { return run(parse(argc, argv)); }
  catch (const std::exception& e) {
    std::cout.flush();
    std::cerr << '\n' << std::string(97, '=') << "\n  miniXiangShan | EMU SETUP ERROR\n"
      << std::string(97, '=') << "\n  Reason: " << e.what()
      << "\n  Runtime comparison snapshots are unavailable because initialization did not complete.\n"
      << "  Run emu --help for image/reference options.\n" << std::string(97, '=') << '\n';
    return 1;
  }
}
