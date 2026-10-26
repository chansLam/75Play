#ifndef DIPLAY_POLY1305_H
#define DIPLAY_POLY1305_H

#include <stddef.h>
#include <stdint.h>

/* Poly1305 one-time authenticator (RFC 8439). */

typedef struct {
    uint32_t r[5];
    uint32_t h[5];
    uint32_t pad[4];
    uint8_t buffer[16];
    size_t buffered;
} poly1305_ctx;

void poly1305_init(poly1305_ctx *ctx, const uint8_t key[32]);
void poly1305_update(poly1305_ctx *ctx, const uint8_t *message, size_t len);
void poly1305_finish(poly1305_ctx *ctx, uint8_t tag[16]);

void poly1305_auth(uint8_t tag[16], const uint8_t *message, size_t len, const uint8_t key[32]);

#endif
