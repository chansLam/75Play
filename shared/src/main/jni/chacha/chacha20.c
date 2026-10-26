#include "chacha20.h"

#include <string.h>

static uint32_t load32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void store32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static uint32_t rotl(uint32_t v, int n) {
    return (v << n) | (v >> (32 - n));
}

#define QR(a, b, c, d)           \
    do {                         \
        a += b;                  \
        d ^= a;                  \
        d = rotl(d, 16);         \
        c += d;                  \
        b ^= c;                  \
        b = rotl(b, 12);         \
        a += b;                  \
        d ^= a;                  \
        d = rotl(d, 8);          \
        c += d;                  \
        b ^= c;                  \
        b = rotl(b, 7);          \
    } while (0)

static void block(uint32_t s[16], const uint8_t key[32], const uint8_t nonce[12], uint32_t counter) {
    s[0] = 0x61707865u;
    s[1] = 0x3320646eu;
    s[2] = 0x79622d32u;
    s[3] = 0x6b206574u;
    for (int i = 0; i < 8; i++) s[4 + i] = load32(key + (size_t)i * 4);
    s[12] = counter;
    s[13] = load32(nonce);
    s[14] = load32(nonce + 4);
    s[15] = load32(nonce + 8);
    uint32_t w[16];
    memcpy(w, s, sizeof w);
    for (int i = 0; i < 10; i++) {
        QR(w[0], w[4], w[8], w[12]);
        QR(w[1], w[5], w[9], w[13]);
        QR(w[2], w[6], w[10], w[14]);
        QR(w[3], w[7], w[11], w[15]);
        QR(w[0], w[5], w[10], w[15]);
        QR(w[1], w[6], w[11], w[12]);
        QR(w[2], w[7], w[8], w[13]);
        QR(w[3], w[4], w[9], w[14]);
    }
    for (int i = 0; i < 16; i++) s[i] = w[i] + s[i];
}

void chacha20_xor_scalar(uint8_t *out, const uint8_t *in, size_t len,
                         const uint8_t key[32], const uint8_t nonce[12], uint32_t counter) {
    uint8_t stream[64];
    size_t offset = 0;
    while (offset < len) {
        uint32_t s[16];
        block(s, key, nonce, counter++);
        for (int i = 0; i < 16; i++) store32(stream + (size_t)i * 4, s[i]);
        size_t n = len - offset;
        if (n > 64) n = 64;
        for (size_t i = 0; i < n; i++) out[offset + i] = in[offset + i] ^ stream[i];
        offset += n;
    }
}

#if defined(__ARM_NEON)

#include <arm_neon.h>

static uint32x4_t rotl16(uint32x4_t v) { return vsriq_n_u32(vshlq_n_u32(v, 16), v, 16); }
static uint32x4_t rotl12(uint32x4_t v) { return vsriq_n_u32(vshlq_n_u32(v, 12), v, 20); }
static uint32x4_t rotl8(uint32x4_t v) { return vsriq_n_u32(vshlq_n_u32(v, 8), v, 24); }
static uint32x4_t rotl7(uint32x4_t v) { return vsriq_n_u32(vshlq_n_u32(v, 7), v, 25); }

static void qr4(uint32x4_t *a, uint32x4_t *b, uint32x4_t *c, uint32x4_t *d) {
    *a = vaddq_u32(*a, *b);
    *d = veorq_u32(*d, *a);
    *d = rotl16(*d);
    *c = vaddq_u32(*c, *d);
    *b = veorq_u32(*b, *c);
    *b = rotl12(*b);
    *a = vaddq_u32(*a, *b);
    *d = veorq_u32(*d, *a);
    *d = rotl8(*d);
    *c = vaddq_u32(*c, *d);
    *b = veorq_u32(*b, *c);
    *b = rotl7(*b);
}

static void xor4(uint8_t *out, const uint8_t *in, const uint8_t key[32], const uint8_t nonce[12], uint32_t counter) {
    uint32_t base[16];
    base[0] = 0x61707865u;
    base[1] = 0x3320646eu;
    base[2] = 0x79622d32u;
    base[3] = 0x6b206574u;
    for (int i = 0; i < 8; i++) base[4 + i] = load32(key + (size_t)i * 4);
    base[12] = counter;
    base[13] = load32(nonce);
    base[14] = load32(nonce + 4);
    base[15] = load32(nonce + 8);
    uint32x4_t s[16];
    uint32x4_t orig[16];
    for (int i = 0; i < 16; i++) {
        uint32_t lanes[4] = {base[i], base[i], base[i], base[i]};
        if (i == 12) {
            lanes[1] = counter + 1;
            lanes[2] = counter + 2;
            lanes[3] = counter + 3;
        }
        s[i] = vld1q_u32(lanes);
        orig[i] = s[i];
    }
    for (int round = 0; round < 10; round++) {
        qr4(&s[0], &s[4], &s[8], &s[12]);
        qr4(&s[1], &s[5], &s[9], &s[13]);
        qr4(&s[2], &s[6], &s[10], &s[14]);
        qr4(&s[3], &s[7], &s[11], &s[15]);
        qr4(&s[0], &s[5], &s[10], &s[15]);
        qr4(&s[1], &s[6], &s[11], &s[12]);
        qr4(&s[2], &s[7], &s[8], &s[13]);
        qr4(&s[3], &s[4], &s[9], &s[14]);
    }
    uint32_t words[4][16];
    for (int i = 0; i < 16; i++) {
        uint32x4_t v = vaddq_u32(s[i], orig[i]);
        uint32_t lanes[4];
        vst1q_u32(lanes, v);
        words[0][i] = lanes[0];
        words[1][i] = lanes[1];
        words[2][i] = lanes[2];
        words[3][i] = lanes[3];
    }
    for (int lane = 0; lane < 4; lane++) {
        uint8_t stream[64];
        for (int i = 0; i < 16; i++) store32(stream + (size_t)i * 4, words[lane][i]);
        const uint8_t *src = in + (size_t)lane * 64;
        uint8_t *dst = out + (size_t)lane * 64;
        for (int i = 0; i < 64; i++) dst[i] = src[i] ^ stream[i];
    }
}

#endif

void chacha20_xor(uint8_t *out, const uint8_t *in, size_t len,
                  const uint8_t key[32], const uint8_t nonce[12], uint32_t counter) {
#if defined(__ARM_NEON)
    while (len >= 256) {
        xor4(out, in, key, nonce, counter);
        out += 256;
        in += 256;
        len -= 256;
        counter += 4;
    }
#endif
    chacha20_xor_scalar(out, in, len, key, nonce, counter);
}
