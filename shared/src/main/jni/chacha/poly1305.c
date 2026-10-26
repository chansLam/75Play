#include "poly1305.h"

#include <string.h>

/* 26-bit limbs. h = (h + block) * r  mod (2^130 - 5). */

static uint32_t load32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void store32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static void absorb(poly1305_ctx *ctx, const uint8_t block[16], uint32_t hibit) {
    uint32_t n0 = load32(block);
    uint32_t n1 = load32(block + 4);
    uint32_t n2 = load32(block + 8);
    uint32_t n3 = load32(block + 12);
    const uint32_t *r = ctx->r;
    const uint32_t s1 = r[1] * 5;
    const uint32_t s2 = r[2] * 5;
    const uint32_t s3 = r[3] * 5;
    const uint32_t s4 = r[4] * 5;
    uint32_t h0 = ctx->h[0] + (n0 & 0x3ffffffu);
    uint32_t h1 = ctx->h[1] + (((n0 >> 26) | (n1 << 6)) & 0x3ffffffu);
    uint32_t h2 = ctx->h[2] + (((n1 >> 20) | (n2 << 12)) & 0x3ffffffu);
    uint32_t h3 = ctx->h[3] + (((n2 >> 14) | (n3 << 18)) & 0x3ffffffu);
    uint32_t h4 = ctx->h[4] + ((n3 >> 8) | hibit);

    uint64_t d0 = (uint64_t)h0 * r[0] + (uint64_t)h1 * s4 + (uint64_t)h2 * s3 + (uint64_t)h3 * s2 + (uint64_t)h4 * s1;
    uint64_t d1 = (uint64_t)h0 * r[1] + (uint64_t)h1 * r[0] + (uint64_t)h2 * s4 + (uint64_t)h3 * s3 + (uint64_t)h4 * s2;
    uint64_t d2 = (uint64_t)h0 * r[2] + (uint64_t)h1 * r[1] + (uint64_t)h2 * r[0] + (uint64_t)h3 * s4 + (uint64_t)h4 * s3;
    uint64_t d3 = (uint64_t)h0 * r[3] + (uint64_t)h1 * r[2] + (uint64_t)h2 * r[1] + (uint64_t)h3 * r[0] + (uint64_t)h4 * s4;
    uint64_t d4 = (uint64_t)h0 * r[4] + (uint64_t)h1 * r[3] + (uint64_t)h2 * r[2] + (uint64_t)h3 * r[1] + (uint64_t)h4 * r[0];

    uint64_t c;
    c = d0 >> 26; h0 = (uint32_t)d0 & 0x3ffffffu; d1 += c;
    c = d1 >> 26; h1 = (uint32_t)d1 & 0x3ffffffu; d2 += c;
    c = d2 >> 26; h2 = (uint32_t)d2 & 0x3ffffffu; d3 += c;
    c = d3 >> 26; h3 = (uint32_t)d3 & 0x3ffffffu; d4 += c;
    c = d4 >> 26; h4 = (uint32_t)d4 & 0x3ffffffu; h0 += (uint32_t)c * 5;
    c = h0 >> 26; h0 &= 0x3ffffffu; h1 += (uint32_t)c;

    ctx->h[0] = h0;
    ctx->h[1] = h1;
    ctx->h[2] = h2;
    ctx->h[3] = h3;
    ctx->h[4] = h4;
}

void poly1305_init(poly1305_ctx *ctx, const uint8_t key[32]) {
    memset(ctx, 0, sizeof *ctx);
    uint32_t t0 = load32(key);
    uint32_t t1 = load32(key + 4);
    uint32_t t2 = load32(key + 8);
    uint32_t t3 = load32(key + 12);
    ctx->r[0] = t0 & 0x3ffffffu;
    ctx->r[1] = ((t0 >> 26) | (t1 << 6)) & 0x3ffff03u;
    ctx->r[2] = ((t1 >> 20) | (t2 << 12)) & 0x3ffc0ffu;
    ctx->r[3] = ((t2 >> 14) | (t3 << 18)) & 0x3f03fffu;
    ctx->r[4] = (t3 >> 8) & 0x00fffffu;
    ctx->pad[0] = load32(key + 16);
    ctx->pad[1] = load32(key + 20);
    ctx->pad[2] = load32(key + 24);
    ctx->pad[3] = load32(key + 28);
}

void poly1305_update(poly1305_ctx *ctx, const uint8_t *message, size_t len) {
    if (ctx->buffered != 0) {
        size_t take = 16 - ctx->buffered;
        if (take > len) take = len;
        memcpy(ctx->buffer + ctx->buffered, message, take);
        ctx->buffered += take;
        message += take;
        len -= take;
        if (ctx->buffered == 16) {
            absorb(ctx, ctx->buffer, 1u << 24);
            ctx->buffered = 0;
        }
    }
    while (len >= 16) {
        absorb(ctx, message, 1u << 24);
        message += 16;
        len -= 16;
    }
    if (len != 0) {
        memcpy(ctx->buffer, message, len);
        ctx->buffered = len;
    }
}

void poly1305_finish(poly1305_ctx *ctx, uint8_t tag[16]) {
    if (ctx->buffered != 0) {
        ctx->buffer[ctx->buffered] = 1;
        for (size_t i = ctx->buffered + 1; i < 16; i++) ctx->buffer[i] = 0;
        absorb(ctx, ctx->buffer, 0);
    }
    uint32_t *h = ctx->h;
    uint32_t c;
    c = h[1] >> 26; h[1] &= 0x3ffffffu; h[2] += c;
    c = h[2] >> 26; h[2] &= 0x3ffffffu; h[3] += c;
    c = h[3] >> 26; h[3] &= 0x3ffffffu; h[4] += c;
    c = h[4] >> 26; h[4] &= 0x3ffffffu; h[0] += c * 5;
    c = h[0] >> 26; h[0] &= 0x3ffffffu; h[1] += c;

    uint32_t g0 = h[0] + 5;
    c = g0 >> 26; g0 &= 0x3ffffffu;
    uint32_t g1 = h[1] + c; c = g1 >> 26; g1 &= 0x3ffffffu;
    uint32_t g2 = h[2] + c; c = g2 >> 26; g2 &= 0x3ffffffu;
    uint32_t g3 = h[3] + c; c = g3 >> 26; g3 &= 0x3ffffffu;
    uint32_t g4 = h[4] + c - (1u << 26);

    uint32_t mask = (g4 >> 31) - 1u;
    g0 &= mask; g1 &= mask; g2 &= mask; g3 &= mask; g4 &= mask;
    mask = ~mask;
    h[0] = (h[0] & mask) | g0;
    h[1] = (h[1] & mask) | g1;
    h[2] = (h[2] & mask) | g2;
    h[3] = (h[3] & mask) | g3;
    h[4] = (h[4] & mask) | g4;

    uint32_t f0 = ((h[0]) | (h[1] << 26)) & 0xffffffffu;
    uint32_t f1 = ((h[1] >> 6) | (h[2] << 20)) & 0xffffffffu;
    uint32_t f2 = ((h[2] >> 12) | (h[3] << 14)) & 0xffffffffu;
    uint32_t f3 = ((h[3] >> 18) | (h[4] << 8)) & 0xffffffffu;

    uint64_t carry = (uint64_t)f0 + ctx->pad[0];
    f0 = (uint32_t)carry;
    carry = (uint64_t)f1 + ctx->pad[1] + (carry >> 32);
    f1 = (uint32_t)carry;
    carry = (uint64_t)f2 + ctx->pad[2] + (carry >> 32);
    f2 = (uint32_t)carry;
    carry = (uint64_t)f3 + ctx->pad[3] + (carry >> 32);
    f3 = (uint32_t)carry;
    store32(tag, f0);
    store32(tag + 4, f1);
    store32(tag + 8, f2);
    store32(tag + 12, f3);
}

void poly1305_auth(uint8_t tag[16], const uint8_t *message, size_t len, const uint8_t key[32]) {
    poly1305_ctx ctx;
    poly1305_init(&ctx, key);
    poly1305_update(&ctx, message, len);
    poly1305_finish(&ctx, tag);
}
