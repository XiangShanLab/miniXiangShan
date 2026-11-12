/*
 * Bare-metal CoreMark port for the miniXiangShan performance harness.
 *
 * Timing scope is CoreMark's own start_time()/stop_time() interval, which
 * encloses iterate() and therefore all three CoreMark workloads.  The counters
 * are reset at start_time(), so cycles and instret are measured from that
 * point rather than from reset.
 */

#include "coremark.h"

#include <stdarg.h>

#if defined(COREMARK_HOST)
#include <stdio.h>
#endif

#if VALIDATION_RUN
volatile ee_s32 seed1_volatile = 0x3415;
volatile ee_s32 seed2_volatile = 0x3415;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PERFORMANCE_RUN
volatile ee_s32 seed1_volatile = 0x0;
volatile ee_s32 seed2_volatile = 0x0;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PROFILE_RUN
volatile ee_s32 seed1_volatile = 0x8;
volatile ee_s32 seed2_volatile = 0x8;
volatile ee_s32 seed3_volatile = 0x8;
#endif
volatile ee_s32 seed4_volatile = ITERATIONS;
#ifndef COREMARK_EXECS
#define COREMARK_EXECS 0
#endif
volatile ee_s32 seed5_volatile = COREMARK_EXECS;

ee_u32 default_num_contexts = 1;

static unsigned long long timed_cycles;
static unsigned long long timed_instret;

#if !defined(COREMARK_HOST)

static inline void reset_counters(void) {
  __asm__ volatile("csrw mcycle, zero\n"
                   "csrw minstret, zero" ::: "memory");
}

static inline unsigned long long read_mcycle64(void) {
  unsigned long long value;
  __asm__ volatile("csrr %0, mcycle" : "=r"(value));
  return value;
}

static inline unsigned long long read_minstret64(void) {
  unsigned long long value;
  __asm__ volatile("csrr %0, minstret" : "=r"(value));
  return value;
}

#endif /* !COREMARK_HOST */

void start_time(void) {
  timed_cycles = 0;
  timed_instret = 0;
#if !defined(COREMARK_HOST)
  reset_counters();
#endif
}

void stop_time(void) {
#if !defined(COREMARK_HOST)
  timed_cycles = read_mcycle64();
  timed_instret = read_minstret64();
#endif
}

CORE_TICKS get_time(void) { return (CORE_TICKS)timed_cycles; }

secs_ret time_in_secs(CORE_TICKS ticks) {
  return (secs_ret)(ticks / EE_TICKS_PER_SEC);
}

void portable_init(core_portable *p, int *argc, char *argv[]) {
  (void)argc;
  (void)argv;
  p->portable_id = 1;
}

void portable_fini(core_portable *p) { p->portable_id = 0; }

#if defined(COREMARK_HOST)

int ee_printf(const char *format, ...) {
  va_list args;
  va_start(args, format);
  const int written = vprintf(format, args);
  va_end(args);
  return written;
}

#else

static void console_putc(char c) {
  volatile uint32_t *console = (volatile uint32_t *)0x4000f038u;
  *console = (uint32_t)(unsigned char)c;
}

static int console_put_uint(unsigned long long value, unsigned int base,
                            int uppercase, int width, char pad) {
  const char *digits = uppercase ? "0123456789ABCDEF" : "0123456789abcdef";
  char buffer[32];
  int length = 0;
  int written = 0;

  do {
    buffer[length++] = digits[value % base];
    value /= base;
  } while (value != 0);

  for (int i = length; i < width; ++i) {
    console_putc(pad);
    ++written;
  }
  while (length > 0) {
    console_putc(buffer[--length]);
    ++written;
  }
  return written;
}

int ee_printf(const char *format, ...) {
  va_list args;
  int written = 0;

  va_start(args, format);
  while (*format != '\0') {
    if (*format != '%') {
      console_putc(*format++);
      ++written;
      continue;
    }

    ++format;
    if (*format == '\0') break;

    char pad = ' ';
    int width = 0;
    if (*format == '0') {
      pad = '0';
      ++format;
    }
    while (*format >= '0' && *format <= '9') {
      width = width * 10 + (*format - '0');
      ++format;
    }

    int long_count = 0;
    if (*format == 'l') {
      long_count = 1;
      ++format;
      if (*format == 'l') {
        long_count = 2;
        ++format;
      }
    }

    switch (*format) {
      case 'c': {
        console_putc((char)va_arg(args, int));
        ++written;
        break;
      }
      case 's': {
        const char *string = va_arg(args, const char *);
        if (string == (const char *)0) string = "(null)";
        const char *end = string;
        while (*end != '\0') ++end;
        for (int i = (int)(end - string); i < width; ++i) {
          console_putc(pad);
          ++written;
        }
        while (string < end) {
          console_putc(*string++);
          ++written;
        }
        break;
      }
      case 'd': {
        long long value;
        unsigned long long magnitude;
        if (long_count == 2) {
          value = va_arg(args, long long);
        } else if (long_count == 1) {
          value = va_arg(args, long);
        } else {
          value = va_arg(args, int);
        }
        if (value < 0) {
          console_putc('-');
          ++written;
          magnitude = (unsigned long long)(-(value + 1)) + 1ull;
        } else {
          magnitude = (unsigned long long)value;
        }
        written += console_put_uint(magnitude, 10u, 0, width, pad);
        break;
      }
      case 'u': {
        unsigned long long value;
        if (long_count == 2) {
          value = va_arg(args, unsigned long long);
        } else if (long_count == 1) {
          value = va_arg(args, unsigned long);
        } else {
          value = va_arg(args, unsigned int);
        }
        written += console_put_uint(value, 10u, 0, width, pad);
        break;
      }
      case 'x': {
        unsigned long long value;
        if (long_count == 2) {
          value = va_arg(args, unsigned long long);
        } else if (long_count == 1) {
          value = va_arg(args, unsigned long);
        } else {
          value = va_arg(args, unsigned int);
        }
        written += console_put_uint(value, 16u, 0, width, pad);
        break;
      }
      case 'X': {
        unsigned long long value;
        if (long_count == 2) {
          value = va_arg(args, unsigned long long);
        } else if (long_count == 1) {
          value = va_arg(args, unsigned long);
        } else {
          value = va_arg(args, unsigned int);
        }
        written += console_put_uint(value, 16u, 1, width, pad);
        break;
      }
      case '%':
        console_putc('%');
        ++written;
        break;
      default:
        console_putc('%');
        console_putc(*format);
        written += 2;
        break;
    }
    ++format;
  }
  va_end(args);
  return written;
}

#endif /* COREMARK_HOST */

void coremark_report(core_results *res, int total_errors, ee_u16 seedcrc) {
#if defined(COREMARK_HOST)
  printf(
      "COREMARK_HOST seedcrc=0x%04x crclist=0x%04x crcmatrix=0x%04x "
      "crcstate=0x%04x crcfinal=0x%04x errors=%d iterations=%u\n",
      (unsigned)seedcrc, (unsigned)res->crclist, (unsigned)res->crcmatrix,
      (unsigned)res->crcstate, (unsigned)res->crc, total_errors,
      (unsigned)res->iterations);
#else
  volatile uint32_t *perf = (volatile uint32_t *)0x4000f000u;
  const uint32_t signature =
      total_errors == 0 ? 0x434d524bu
                        : (0xbad00000u | ((uint32_t)total_errors & 0xfffu));

  perf[0] = signature;
  perf[2] = (uint32_t)timed_cycles;
  perf[4] = total_errors == 0
                ? (uint32_t)(timed_cycles >> 32)
                : (((uint32_t)res->crclist << 16) | res->crcmatrix);
  perf[6] = (uint32_t)timed_instret;
  perf[8] = total_errors == 0 ? (uint32_t)(timed_instret >> 32)
                              : ((uint32_t)res->crcstate << 16);
  perf[10] = (uint32_t)res->crc;
  __asm__ volatile("fence" ::: "memory");
  perf[12] = 0x600d600du;
#endif
}
