#ifndef DIPLAY_AEAD_H
#define DIPLAY_AEAD_H

#include <stddef.h>
#include <stdint.h>

/* Original ChaCha20-Poly1305 as in RFC 8439, not a copy of libsodium, BoringSSL, or donna.
 * out for seal is plaintext_len + 16.
 * open returns 0, or -1 when the tag does not match (out is then wiped). */

int aead_seal(const uint8_t key[32], const uint8_t nonce[12],
              const uint8_t *aad, size_t aad_len,
              const uint8_t *plain, size_t plain_len,
              uint8_t *out);

int aead_open(const uint8_t key[32], const uint8_t nonce[12],
              const uint8_t *aad, size_t aad_len,
              const uint8_t *sealed, size_t sealed_len,
              uint8_t *out);

/* 0 when the RFC 8439 vector matches and, on NEON, a long block matches scalar. */
int aead_selftest(void);

#endif
