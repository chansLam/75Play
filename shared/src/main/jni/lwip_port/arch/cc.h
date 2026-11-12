#ifndef LWIP_ARCH_CC_H
#define LWIP_ARCH_CC_H

#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <endian.h>

#ifndef BYTE_ORDER
#if defined(__BYTE_ORDER) && (__BYTE_ORDER == __BIG_ENDIAN)
#define BYTE_ORDER BIG_ENDIAN
#else
#define BYTE_ORDER LITTLE_ENDIAN
#endif
#endif

typedef uint8_t   u8_t;
typedef int8_t    s8_t;
typedef uint16_t  u16_t;
typedef int16_t   s16_t;
typedef uint32_t  u32_t;
typedef int32_t   s32_t;
typedef uintptr_t mem_ptr_t;

#define U16_F "hu"
#define S16_F "hd"
#define X16_F "hx"
#define U32_F "u"
#define S32_F "d"
#define X32_F "x"
#define SZT_F "zu"

#define PACK_STRUCT_BEGIN
#define PACK_STRUCT_STRUCT __attribute__((packed))
#define PACK_STRUCT_END
#define PACK_STRUCT_FIELD(x) x

#define LWIP_PLATFORM_ASSERT(x) do { \
    fprintf(stderr, "Assertion \"%s\" failed at line %d in %s\n", x, __LINE__, __FILE__); \
    abort(); \
} while (0)

#define LWIP_PLATFORM_DIAG(x) do { printf x; } while (0)

#define LWIP_RAND() ((u32_t)rand())

static inline u32_t sys_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (u32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

typedef int sys_prot_t;
#define SYS_ARCH_DECL_PROTECT(lev) sys_prot_t lev
#define SYS_ARCH_PROTECT(lev) ((void)lev)
#define SYS_ARCH_UNPROTECT(lev) ((void)lev)

#endif /* LWIP_ARCH_CC_H */
