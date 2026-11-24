#include "mini.h"

void mini_puts(const char *text) {
  volatile uint32_t *console = (volatile uint32_t *)0x4000f038u;
  while (*text) *console = (unsigned char)*text++;
}

void mini_perf_begin(void) {
  __asm__ volatile("csrw mcycle, zero\ncsrw minstret, zero" ::: "memory");
}

void mini_perf_end(uint32_t checksum, int failed) {
  uint64_t cycles, instret;
  __asm__ volatile("csrr %0, mcycle" : "=r"(cycles));
  __asm__ volatile("csrr %0, minstret" : "=r"(instret));
  volatile uint32_t *perf = (volatile uint32_t *)0x4000f000u;
  perf[0] = failed ? 0xbad00001u : 0x434d524bu;
  perf[2] = (uint32_t)cycles;
  perf[4] = (uint32_t)(cycles >> 32);
  perf[6] = (uint32_t)instret;
  perf[8] = (uint32_t)(instret >> 32);
  perf[10] = checksum;
  __asm__ volatile("fence" ::: "memory");
  perf[12] = 0x600d600du;
}
