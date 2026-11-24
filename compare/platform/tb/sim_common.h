#pragma once

#include <verilated.h>

#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

template <typename Top>
static int run_perf_top(Top* top, int argc, char** argv) {
  const char* name = "unknown";
  const char* trace_path = nullptr;
  uint64_t max_cycles = 5000000;
  uint64_t trace_start_pc = 0;
  uint64_t trace_stop_pc = 0;
  for (int i = 1; i < argc; ++i) {
    if (std::strncmp(argv[i], "+name=", 6) == 0) name = argv[i] + 6;
    if (std::strncmp(argv[i], "+max_cycles=", 12) == 0)
      max_cycles = std::strtoull(argv[i] + 12, nullptr, 0);
    if (std::strncmp(argv[i], "+trace=", 7) == 0) trace_path = argv[i] + 7;
    if (std::strncmp(argv[i], "+trace_start_pc=", 16) == 0)
      trace_start_pc = std::strtoull(argv[i] + 16, nullptr, 0);
    if (std::strncmp(argv[i], "+trace_stop_pc=", 15) == 0)
      trace_stop_pc = std::strtoull(argv[i] + 15, nullptr, 0);
  }

  FILE* trace = nullptr;
  if (trace_path != nullptr) {
    trace = std::fopen(trace_path, "wb");
    if (trace == nullptr) {
      std::fprintf(stderr, "ERROR: cannot open trace file %s\n", trace_path);
      return 1;
    }
    static char trace_buffer[1 << 20];
    std::setvbuf(trace, trace_buffer, _IOFBF, sizeof(trace_buffer));
    std::fprintf(trace,
                 "# retire-window trace v1\n"
                 "# columns: sim_cycle lane_count (pc inst)*\n"
                 "# trace_start_pc=0x%016" PRIx64 " trace_stop_pc=0x%016" PRIx64
                 "\n",
                 trace_start_pc, trace_stop_pc);
  }

  bool trace_active = false;
  bool trace_done = false;
  bool trace_first_window = true;
  int trace_start_lane = 0;
  uint64_t trace_windows = 0;
  uint64_t trace_retired = 0;
  uint64_t trace_end_cycle = 0;
  uint64_t sim_cycles = 0;

  auto trace_lane_valid = [&](int lane) -> bool {
    switch (lane) {
      case 0: return top->trace_valid & 1u;
      case 1: return top->trace_valid & 2u;
      case 2: return top->trace_valid & 4u;
      default: return false;
    }
  };
  auto trace_lane_pc = [&](int lane) -> uint64_t {
    switch (lane) {
      case 0: return top->trace_pc_0;
      case 1: return top->trace_pc_1;
      case 2: return top->trace_pc_2;
      default: return 0;
    }
  };
  auto trace_lane_inst = [&](int lane) -> uint32_t {
    switch (lane) {
      case 0: return top->trace_inst_0;
      case 1: return top->trace_inst_1;
      case 2: return top->trace_inst_2;
      default: return 0;
    }
  };

  auto tick = [&]() {
    top->aclk = 0;
    top->eval();
    // Sample before the rising edge: trace outputs then describe the commit
    // window that the edge will retire, not the following ROB head.
    if (trace != nullptr && !trace_done) {
      if (!trace_active) {
        for (int lane = 0; lane < 3; ++lane) {
          if (trace_lane_valid(lane) && trace_lane_pc(lane) == trace_start_pc) {
            trace_active = true;
            trace_start_lane = lane;
            break;
          }
        }
      }

      if (trace_active) {
        const int first_lane = trace_first_window ? trace_start_lane : 0;
        uint64_t pcs[3] = {0, 0, 0};
        uint32_t insts[3] = {0, 0, 0};
        int count = 0;
        bool stop = false;
        for (int lane = first_lane; lane < 3; ++lane) {
          if (!trace_lane_valid(lane)) continue;
          if (trace_lane_pc(lane) == trace_stop_pc) {
            stop = true;
            break;
          }
          pcs[count] = trace_lane_pc(lane);
          insts[count] = trace_lane_inst(lane);
          ++count;
        }

        if (count != 0) {
          std::fprintf(trace, "%" PRIu64 "\t%d", sim_cycles, count);
          for (int lane = 0; lane < count; ++lane) {
            std::fprintf(trace, "\t0x%016" PRIx64 "\t0x%08x", pcs[lane],
                         insts[lane]);
          }
          std::fputc('\n', trace);
          ++trace_windows;
          trace_retired += uint64_t(count);
        }

        if (stop) {
          trace_done = true;
          trace_active = false;
          trace_end_cycle = sim_cycles;
          std::fflush(trace);
        } else if (trace_active) {
          trace_first_window = false;
        }
      }
    }
    top->aclk = 1;
    top->eval();
  };

  top->aresetn = 0;
  top->aclk = 0;
  top->eval();
  for (int i = 0; i < 8; ++i) tick();
  top->aresetn = 1;

  while (!top->perf_done && sim_cycles < max_cycles) {
    tick();
    ++sim_cycles;
    if ((sim_cycles % 1000000) == 0) {
      std::fprintf(stderr, "[%s] simulation cycles=%" PRIu64 "\n", name,
                   sim_cycles);
    }
  }

  // Let the final AXI response settle before sampling the MMIO registers.
  for (int i = 0; i < 4; ++i) tick();

  const uint64_t cycles = (uint64_t(top->perf_cycle_hi) << 32) |
                          uint64_t(top->perf_cycle_lo);
  const uint64_t instret = (uint64_t(top->perf_instret_hi) << 32) |
                           uint64_t(top->perf_instret_lo);
  const double ipc = cycles ? double(instret) / double(cycles) : 0.0;

  std::printf(
      "RESULT name=%s status=%s cycles=%" PRIu64
      " instret=%" PRIu64 " ipc=%.6f signature=0x%08x checksum=0x%08x"
      " sim_cycles=%" PRIu64 "\n",
      name, top->perf_done ? "ok" : "timeout", cycles, instret, ipc,
      uint32_t(top->perf_signature), uint32_t(top->perf_checksum),
      sim_cycles);

  if (trace != nullptr) {
    std::printf("TRACE name=%s status=%s windows=%" PRIu64
                " retired=%" PRIu64 " end_cycle=%" PRIu64 "\n",
                name, trace_done ? "ok" : "incomplete", trace_windows,
                trace_retired, trace_end_cycle);
    std::fclose(trace);
  }

  const int rc = top->perf_done ? 0 : 2;
  delete top;
  return rc;
}
