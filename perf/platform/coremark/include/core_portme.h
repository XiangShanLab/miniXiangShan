/*
 * Bare-metal CoreMark port for the shared NutShell/miniXiangShan
 * Axi4PerfPlatform.
 *
 * The CoreMark algorithm sources are kept unchanged.  This header only
 * selects the memory, seed, timing and reporting configuration.
 */

#ifndef CORE_PORTME_H
#define CORE_PORTME_H

#include <stddef.h>
#include <stdint.h>

#ifndef ITERATIONS
#define ITERATIONS 10
#endif

#define HAS_FLOAT 0
#define HAS_TIME_H 0
#define USE_CLOCK 0
#define HAS_STDIO 0
#define HAS_PRINTF 0
#define MEM_METHOD MEM_STATIC
#define SEED_METHOD SEED_VOLATILE
#define MULTITHREAD 1
#define MAIN_HAS_NOARGC 1
#define MAIN_HAS_NORETURN 0
#define CORE_DEBUG 0

/* The bare-metal implementation writes to the platform's console MMIO
 * register.  The host reference build forwards to libc's vprintf instead. */
int ee_printf(const char *format, ...);

/* CoreMark's report prints time_in_secs() as milliseconds.  Normalize the
 * mcycle tick at a 1 GHz reporting frequency so the printed field becomes
 * cycles / 1e6.  This is not a measured wall-clock frequency; all performance
 * comparisons continue to use mcycle directly. */
#define EE_TICKS_PER_SEC 1000000u

typedef uint32_t CORE_TICKS;

#ifndef COMPILER_VERSION
#ifdef __GNUC__
#define COMPILER_VERSION "GCC " __VERSION__
#else
#define COMPILER_VERSION "unknown compiler"
#endif
#endif
#ifndef COMPILER_FLAGS
#define COMPILER_FLAGS "-O2 -ffreestanding -fno-builtin"
#endif
#ifndef MEM_LOCATION
#define MEM_LOCATION "DRAM static data"
#endif

typedef int16_t ee_s16;
typedef uint16_t ee_u16;
typedef int32_t ee_s32;
typedef float ee_f32;
typedef uint8_t ee_u8;
typedef uint32_t ee_u32;
typedef uintptr_t ee_ptr_int;
typedef size_t ee_size_t;

#define align_mem(x) \
  (void *)(uintptr_t)(4u + (((uintptr_t)(x)-1u) & ~(uintptr_t)3u))

extern ee_u32 default_num_contexts;

typedef struct CORE_PORTABLE_S {
  ee_u8 portable_id;
} core_portable;

void portable_init(core_portable *p, int *argc, char *argv[]);
void portable_fini(core_portable *p);

struct RESULTS_S;
void coremark_report(struct RESULTS_S *res, int total_errors, ee_u16 seedcrc);

#if !defined(PROFILE_RUN) && !defined(PERFORMANCE_RUN) && !defined(VALIDATION_RUN)
#if (TOTAL_DATA_SIZE == 1200)
#define PROFILE_RUN 1
#elif (TOTAL_DATA_SIZE == 2000)
#define PERFORMANCE_RUN 1
#else
#define VALIDATION_RUN 1
#endif
#endif

#endif /* CORE_PORTME_H */
