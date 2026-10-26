#include "aead.h"

#include "chacha20.h"
#include "poly1305.h"

#include <string.h>

static void store64(uint8_t *p, uint64_t v) {
    for (int i = 0; i < 8; i++) p[i] = (uint8_t)(v >> (8 * i));
}

static void pad16(poly1305_ctx *mac, size_t len) {
    size_t extra = (16 - (len & 15)) & 15;
    if (extra == 0) return;
    uint8_t zeros[16];
    memset(zeros, 0, extra);
    poly1305_update(mac, zeros, extra);
}

static void otk(uint8_t block0[64], const uint8_t key[32], const uint8_t nonce[12]) {
    uint8_t zeros[64];
    memset(zeros, 0, sizeof zeros);
    chacha20_xor(block0, zeros, 64, key, nonce, 0);
}

static void mac_message(uint8_t tag[16], const uint8_t one_time_key[32],
                        const uint8_t *aad, size_t aad_len,
                        const uint8_t *cipher, size_t cipher_len) {
    poly1305_ctx mac;
    poly1305_init(&mac, one_time_key);
    if (aad_len != 0) poly1305_update(&mac, aad, aad_len);
    pad16(&mac, aad_len);
    if (cipher_len != 0) poly1305_update(&mac, cipher, cipher_len);
    pad16(&mac, cipher_len);
    uint8_t lengths[16];
    store64(lengths, (uint64_t)aad_len);
    store64(lengths + 8, (uint64_t)cipher_len);
    poly1305_update(&mac, lengths, 16);
    poly1305_finish(&mac, tag);
}

int aead_seal(const uint8_t key[32], const uint8_t nonce[12],
              const uint8_t *aad, size_t aad_len,
              const uint8_t *plain, size_t plain_len,
              uint8_t *out) {
    uint8_t block0[64];
    otk(block0, key, nonce);
    if (plain_len != 0) chacha20_xor(out, plain, plain_len, key, nonce, 1);
    mac_message(out + plain_len, block0, aad, aad_len, out, plain_len);
    return 0;
}

int aead_open(const uint8_t key[32], const uint8_t nonce[12],
              const uint8_t *aad, size_t aad_len,
              const uint8_t *sealed, size_t sealed_len,
              uint8_t *out) {
    if (sealed_len < 16) return -1;
    size_t plain_len = sealed_len - 16;
    uint8_t block0[64];
    otk(block0, key, nonce);
    uint8_t tag[16];
    mac_message(tag, block0, aad, aad_len, sealed, plain_len);
    uint8_t diff = 0;
    for (int i = 0; i < 16; i++) diff |= (uint8_t)(tag[i] ^ sealed[plain_len + (size_t)i]);
    if (diff != 0) {
        if (plain_len != 0 && out != NULL) memset(out, 0, plain_len);
        return -1;
    }
    if (plain_len != 0) chacha20_xor(out, sealed, plain_len, key, nonce, 1);
    return 0;
}

int aead_selftest(void) {
    static const uint8_t key[32] = {
        0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f,
        0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f,
    };
    static const uint8_t nonce[12] = {0x07, 0x00, 0x00, 0x00, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47};
    static const uint8_t aad[12] = {0x50, 0x51, 0x52, 0x53, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7};
    static const char *text =
        "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    size_t plain_len = 114;
    static const uint8_t expect_tag[16] = {
        0x1a, 0xe1, 0x0b, 0x59, 0x4f, 0x09, 0xe2, 0x6a, 0x7e, 0x90, 0x2e, 0xcb, 0xd0, 0x60, 0x06, 0x91,
    };
    uint8_t sealed[114 + 16];
    uint8_t opened[114];
    if (aead_seal(key, nonce, aad, sizeof aad, (const uint8_t *)text, plain_len, sealed) != 0) return -1;
    if (memcmp(sealed + plain_len, expect_tag, 16) != 0) return -1;
    if (aead_open(key, nonce, aad, sizeof aad, sealed, plain_len + 16, opened) != 0) return -1;
    if (memcmp(opened, text, plain_len) != 0) return -1;
    sealed[10] ^= 1;
    if (aead_open(key, nonce, aad, sizeof aad, sealed, plain_len + 16, opened) == 0) return -1;

    uint8_t long_in[1024];
    uint8_t scalar[1024];
    uint8_t mixed[1024];
    for (int i = 0; i < 1024; i++) long_in[i] = (uint8_t)(i * 17 + 3);
    chacha20_xor_scalar(scalar, long_in, sizeof long_in, key, nonce, 1);
    chacha20_xor(mixed, long_in, sizeof long_in, key, nonce, 1);
    if (memcmp(scalar, mixed, sizeof long_in) != 0) return -1;

    uint8_t empty_sealed[16];
    if (aead_seal(key, nonce, NULL, 0, NULL, 0, empty_sealed) != 0) return -1;
    if (aead_open(key, nonce, NULL, 0, empty_sealed, 16, NULL) != 0) return -1;
    return 0;
}
