#ifndef MINI_APP_H
#define MINI_APP_H
#include <stdint.h>

void mini_puts(const char *text);
void mini_perf_begin(void);
void mini_perf_end(uint32_t checksum, int failed);
#endif
