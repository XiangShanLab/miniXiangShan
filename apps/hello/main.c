#include "mini.h"

__attribute__((noinline)) uint32_t benchmark(void) {
  volatile uint32_t sum = 0;
  for (uint32_t i = 1; i <= 100; ++i) sum += i;
  return sum;
}

int main(void) {
  mini_puts("hello, miniXiangShan!\n");
  mini_perf_begin();
  uint32_t sum = benchmark();
  mini_perf_end(sum, sum != 5050);
  return sum != 5050;
}
