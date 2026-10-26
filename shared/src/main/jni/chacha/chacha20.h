#ifndef DIPLAY_CHACHA20_H
#define DIPLAY_CHACHA20_H

#include <stddef.h>
#include <stdint.h>

/* IETF ChaCha20 (RFC 8439). Original implementation for 75Play; not a copy of
 * BoringSSL, libsodium, or another cipher library. armeabi-v7a uses a 4-block
 * NEON path when the compiler defines __ARM_NEON. */

void chacha20_xor(uint8_t *out, const uint8_t *in, size_t len,
                  const uint8_t key[32], const uint8_t nonce[12], uint32_t counter);

/* Scalar-only, used to check the NEON path at load time. */
void chacha20_xor_scalar(uint8_t *out, const uint8_t *in, size_t len,
                         const uint8_t key[32], const uint8_t nonce[12], uint32_t counter);

#endif
