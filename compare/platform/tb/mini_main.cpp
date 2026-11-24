#include "VMiniPerfTop.h"
#include "sim_common.h"

int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);
  auto* top = new VMiniPerfTop;
  return run_perf_top(top, argc, argv);
}
