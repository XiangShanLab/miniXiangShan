#include "VNutShellPerfTop.h"
#include "sim_common.h"

int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);
  auto* top = new VNutShellPerfTop;
  return run_perf_top(top, argc, argv);
}
