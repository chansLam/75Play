#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "chacha/aead.h"

/* airplay_chacha: IETF ChaCha20-Poly1305 for the media path. Tag failure returns NULL
 * so Kotlin can throw without naming AEADBadTagException (that class fails Dalvik
 * verification on API 19). */

static int copy_fixed(JNIEnv *env, jbyteArray array, uint8_t *dst, jsize expect) {
    if (array == NULL || (*env)->GetArrayLength(env, array) != expect) return -1;
    (*env)->GetByteArrayRegion(env, array, 0, expect, (jbyte *)dst);
    return (*env)->ExceptionCheck(env) ? -1 : 0;
}

static uint8_t *bytes_of(JNIEnv *env, jbyteArray array, jsize *out_len) {
    if (array == NULL) {
        *out_len = 0;
        return NULL;
    }
    jsize len = (*env)->GetArrayLength(env, array);
    *out_len = len;
    if (len == 0) return NULL;
    uint8_t *buf = (uint8_t *)malloc((size_t)len);
    if (buf == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, array, 0, len, (jbyte *)buf);
    if ((*env)->ExceptionCheck(env)) {
        free(buf);
        return NULL;
    }
    return buf;
}

static jbyteArray seal_arrays(JNIEnv *env, jbyteArray key, jbyteArray nonce, jbyteArray aad, jbyteArray plain) {
    uint8_t key_bytes[32];
    uint8_t nonce_bytes[12];
    if (copy_fixed(env, key, key_bytes, 32) != 0 || copy_fixed(env, nonce, nonce_bytes, 12) != 0) return NULL;
    jsize aad_len = 0;
    jsize plain_len = 0;
    uint8_t *aad_bytes = bytes_of(env, aad, &aad_len);
    uint8_t *plain_bytes = bytes_of(env, plain, &plain_len);
    if ((aad != NULL && aad_len > 0 && aad_bytes == NULL) || (plain != NULL && plain_len > 0 && plain_bytes == NULL)) {
        free(aad_bytes);
        free(plain_bytes);
        return NULL;
    }
    if (plain_len < 0 || aad_len < 0 || (size_t)plain_len > 8u * 1024u * 1024u) {
        free(aad_bytes);
        free(plain_bytes);
        return NULL;
    }
    uint8_t *out = (uint8_t *)malloc((size_t)plain_len + 16);
    jbyteArray result = NULL;
    if (out != NULL && aead_seal(key_bytes, nonce_bytes, aad_bytes, (size_t)aad_len, plain_bytes, (size_t)plain_len, out) == 0) {
        result = (*env)->NewByteArray(env, plain_len + 16);
        if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, plain_len + 16, (const jbyte *)out);
    }
    free(aad_bytes);
    free(plain_bytes);
    free(out);
    return result;
}

static jbyteArray open_arrays(JNIEnv *env, jbyteArray key, jbyteArray nonce, jbyteArray aad, jbyteArray sealed) {
    uint8_t key_bytes[32];
    uint8_t nonce_bytes[12];
    if (copy_fixed(env, key, key_bytes, 32) != 0 || copy_fixed(env, nonce, nonce_bytes, 12) != 0) return NULL;
    jsize aad_len = 0;
    jsize sealed_len = 0;
    uint8_t *aad_bytes = bytes_of(env, aad, &aad_len);
    uint8_t *sealed_bytes = bytes_of(env, sealed, &sealed_len);
    if ((aad != NULL && aad_len > 0 && aad_bytes == NULL) || sealed_bytes == NULL || sealed_len < 16) {
        free(aad_bytes);
        free(sealed_bytes);
        return NULL;
    }
    size_t plain_len = (size_t)sealed_len - 16;
    uint8_t *out = plain_len == 0 ? NULL : (uint8_t *)malloc(plain_len);
    jbyteArray result = NULL;
    if ((plain_len == 0 || out != NULL) &&
        aead_open(key_bytes, nonce_bytes, aad_bytes, (size_t)aad_len, sealed_bytes, (size_t)sealed_len, out) == 0) {
        result = (*env)->NewByteArray(env, (jsize)plain_len);
        if (result != NULL && plain_len != 0) (*env)->SetByteArrayRegion(env, result, 0, (jsize)plain_len, (const jbyte *)out);
    }
    free(aad_bytes);
    free(sealed_bytes);
    free(out);
    return result;
}

/* Direct buffers: ciphertext and plaintext live in caller-owned native memory.
 * Returns plaintext length, or -1 when the tag does not match. */
static jint open_direct(JNIEnv *env, jbyteArray key, jbyteArray nonce, jbyteArray aad,
                        jobject input, jint input_length, jobject output) {
    uint8_t key_bytes[32];
    uint8_t nonce_bytes[12];
    if (copy_fixed(env, key, key_bytes, 32) != 0 || copy_fixed(env, nonce, nonce_bytes, 12) != 0) return -1;
    if (input == NULL || output == NULL || input_length < 16) return -1;
    uint8_t *in = (uint8_t *)(*env)->GetDirectBufferAddress(env, input);
    uint8_t *out = (uint8_t *)(*env)->GetDirectBufferAddress(env, output);
    jlong in_cap = (*env)->GetDirectBufferCapacity(env, input);
    jlong out_cap = (*env)->GetDirectBufferCapacity(env, output);
    if (in == NULL || out == NULL || input_length > in_cap) return -1;
    jsize plain_len = input_length - 16;
    if ((jlong)plain_len > out_cap) return -1;
    jsize aad_len = 0;
    uint8_t *aad_bytes = bytes_of(env, aad, &aad_len);
    if (aad != NULL && aad_len > 0 && aad_bytes == NULL) return -1;
    int opened = aead_open(key_bytes, nonce_bytes, aad_bytes, (size_t)aad_len, in, (size_t)input_length, out);
    free(aad_bytes);
    return opened == 0 ? plain_len : -1;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    if (aead_selftest() != 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shihab_diplay_airplay_NativeChaCha_nativeSeal(
        JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce, jbyteArray aad, jbyteArray plain) {
    (void)clazz;
    return seal_arrays(env, key, nonce, aad, plain);
}

JNIEXPORT jbyteArray JNICALL
Java_com_shihab_diplay_airplay_NativeChaCha_nativeOpen(
        JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce, jbyteArray aad, jbyteArray sealed) {
    (void)clazz;
    return open_arrays(env, key, nonce, aad, sealed);
}

JNIEXPORT jbyteArray JNICALL
Java_com_shihab_diplay_airplay_NativeChaCha_nativeOpenRange(
        JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce,
        jbyteArray aad, jint aad_offset, jint aad_length,
        jbyteArray sealed, jint sealed_offset, jint sealed_length) {
    (void)clazz;
    uint8_t key_bytes[32];
    uint8_t nonce_bytes[12];
    if (copy_fixed(env, key, key_bytes, 32) != 0 || copy_fixed(env, nonce, nonce_bytes, 12) != 0) return NULL;
    if (aad_length < 0 || sealed_length < 16 || aad_offset < 0 || sealed_offset < 0) return NULL;
    if (aad != NULL && aad_offset + aad_length > (*env)->GetArrayLength(env, aad)) return NULL;
    if (sealed == NULL || sealed_offset + sealed_length > (*env)->GetArrayLength(env, sealed)) return NULL;
    uint8_t *aad_bytes = NULL;
    if (aad_length > 0) {
        aad_bytes = (uint8_t *)malloc((size_t)aad_length);
        if (aad_bytes == NULL) return NULL;
        (*env)->GetByteArrayRegion(env, aad, aad_offset, aad_length, (jbyte *)aad_bytes);
    }
    uint8_t *sealed_bytes = (uint8_t *)malloc((size_t)sealed_length);
    if (sealed_bytes == NULL) {
        free(aad_bytes);
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, sealed, sealed_offset, sealed_length, (jbyte *)sealed_bytes);
    if ((*env)->ExceptionCheck(env)) {
        free(aad_bytes);
        free(sealed_bytes);
        return NULL;
    }
    size_t plain_len = (size_t)sealed_length - 16;
    uint8_t *out = plain_len == 0 ? NULL : (uint8_t *)malloc(plain_len);
    jbyteArray result = NULL;
    if ((plain_len == 0 || out != NULL) &&
        aead_open(key_bytes, nonce_bytes, aad_bytes, (size_t)aad_length, sealed_bytes, (size_t)sealed_length, out) == 0) {
        result = (*env)->NewByteArray(env, (jsize)plain_len);
        if (result != NULL && plain_len != 0) (*env)->SetByteArrayRegion(env, result, 0, (jsize)plain_len, (const jbyte *)out);
    }
    free(aad_bytes);
    free(sealed_bytes);
    free(out);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_shihab_diplay_airplay_NativeChaCha_nativeOpenRangeInto(
        JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce,
        jbyteArray aad, jint aad_offset, jint aad_length,
        jbyteArray sealed, jint sealed_offset, jint sealed_length,
        jbyteArray destination, jint destination_offset) {
    (void)clazz;
    uint8_t key_bytes[32];
    uint8_t nonce_bytes[12];
    uint8_t aad_stack[64];
    uint8_t sealed_stack[4096];
    if (copy_fixed(env, key, key_bytes, 32) != 0 || copy_fixed(env, nonce, nonce_bytes, 12) != 0) return -1;
    if (aad_length < 0 || aad_length > 64 || sealed_length < 16 || sealed_length > 4096 ||
        aad_offset < 0 || sealed_offset < 0 || destination_offset < 0 || destination == NULL) return -1;
    if (aad != NULL && aad_offset + aad_length > (*env)->GetArrayLength(env, aad)) return -1;
    if (sealed == NULL || sealed_offset + sealed_length > (*env)->GetArrayLength(env, sealed)) return -1;
    jsize plain_len = sealed_length - 16;
    if (destination_offset > (*env)->GetArrayLength(env, destination) - plain_len) return -1;
    if (aad_length > 0) (*env)->GetByteArrayRegion(env, aad, aad_offset, aad_length, (jbyte *)aad_stack);
    (*env)->GetByteArrayRegion(env, sealed, sealed_offset, sealed_length, (jbyte *)sealed_stack);
    if ((*env)->ExceptionCheck(env)) return -1;
    uint8_t plain_stack[4096];
    uint8_t *plain = plain_len == 0 ? NULL : plain_stack;
    int opened = aead_open(key_bytes, nonce_bytes, aad_length > 0 ? aad_stack : NULL, (size_t)aad_length,
                           sealed_stack, (size_t)sealed_length, plain);
    if (opened == 0 && plain_len != 0) {
        (*env)->SetByteArrayRegion(env, destination, destination_offset, plain_len, (const jbyte *)plain);
    }
    return opened == 0 && !(*env)->ExceptionCheck(env) ? plain_len : -1;
}

/* Microphone frames: nonce is the little-endian counter. Ciphertext and tag are written
 * into the caller packet, so the uplink does not allocate a sealed array per packet. */
JNIEXPORT jint JNICALL
Java_com_shihab_diplay_airplay_NativeChaCha_nativeSealInto(
        JNIEnv *env, jclass clazz, jbyteArray key, jlong counter,
        jbyteArray aad, jint aad_offset, jint aad_length,
        jbyteArray plain, jint plain_offset, jint plain_length,
        jbyteArray destination, jint destination_offset) {
    (void)clazz;
    uint8_t key_bytes[32];
    uint8_t nonce_bytes[12];
    uint8_t aad_stack[64];
    uint8_t plain_stack[4096];
    uint8_t sealed_stack[4096 + 16];
    if (copy_fixed(env, key, key_bytes, 32) != 0) return -1;
    if (plain_length < 0 || plain_length > 4096 || aad_length < 0 || aad_length > 64) return -1;
    if (aad_offset < 0 || plain_offset < 0 || destination_offset < 0) return -1;
    if (plain == NULL || destination == NULL) return -1;
    if (plain_offset > (*env)->GetArrayLength(env, plain) - plain_length) return -1;
    if (destination_offset > (*env)->GetArrayLength(env, destination) - (plain_length + 16)) return -1;
    if (aad_length > 0 && (aad == NULL || aad_offset > (*env)->GetArrayLength(env, aad) - aad_length)) return -1;
    memset(nonce_bytes, 0, sizeof(nonce_bytes));
    uint64_t value = (uint64_t)counter;
    for (int index = 4; index < 12; index++) {
        nonce_bytes[index] = (uint8_t)value;
        value >>= 8;
    }
    if (aad_length > 0) {
        (*env)->GetByteArrayRegion(env, aad, aad_offset, aad_length, (jbyte *)aad_stack);
        if ((*env)->ExceptionCheck(env)) return -1;
    }
    if (plain_length > 0) {
        (*env)->GetByteArrayRegion(env, plain, plain_offset, plain_length, (jbyte *)plain_stack);
        if ((*env)->ExceptionCheck(env)) return -1;
    }
    if (aead_seal(key_bytes, nonce_bytes, aad_length > 0 ? aad_stack : NULL, (size_t)aad_length,
                  plain_length > 0 ? plain_stack : NULL, (size_t)plain_length, sealed_stack) != 0) {
        return -1;
    }
    (*env)->SetByteArrayRegion(env, destination, destination_offset, plain_length + 16, (const jbyte *)sealed_stack);
    return (*env)->ExceptionCheck(env) ? -1 : plain_length + 16;
}

/* One reused ciphertext buffer for screen frames. Two screen threads share it. */
static pthread_mutex_t frame_mu = PTHREAD_MUTEX_INITIALIZER;
static uint8_t *frame_scratch = NULL;
static size_t frame_scratch_cap = 0;

static int ensure_frame_scratch(size_t need) {
    if (frame_scratch_cap >= need) return 0;
    size_t cap = frame_scratch_cap == 0 ? (size_t)64 * 1024 : frame_scratch_cap;
    while (cap < need) {
        if (cap >= (size_t)8 * 1024 * 1024) return -1;
        cap *= 2;
    }
    uint8_t *next = (uint8_t *)realloc(frame_scratch, cap);
    if (next == NULL) return -1;
    frame_scratch = next;
    frame_scratch_cap = cap;
    return 0;
}

/* Screen frames: nonce is the little-endian counter, plaintext is written over the
 * reused scratch after the tag check, then copied once into the caller's array. */
JNIEXPORT jint JNICALL
Java_com_shihab_diplay_airplay_NativeChaCha_nativeOpenFrameInto(
        JNIEnv *env, jclass clazz, jbyteArray key, jlong counter, jbyteArray aad,
        jbyteArray sealed, jint sealed_length, jbyteArray destination) {
    (void)clazz;
    uint8_t key_bytes[32];
    uint8_t nonce_bytes[12];
    uint8_t aad_stack[256];
    if (copy_fixed(env, key, key_bytes, 32) != 0) return -1;
    if (sealed == NULL || destination == NULL || sealed_length < 16) return -1;
    if (sealed_length > (*env)->GetArrayLength(env, sealed)) return -1;
    jsize plain_len = sealed_length - 16;
    if (plain_len > (*env)->GetArrayLength(env, destination)) return -1;
    jsize aad_len = aad == NULL ? 0 : (*env)->GetArrayLength(env, aad);
    if (aad_len < 0 || aad_len > (jsize)sizeof(aad_stack)) return -1;
    memset(nonce_bytes, 0, sizeof(nonce_bytes));
    uint64_t value = (uint64_t)counter;
    for (int index = 4; index < 12; index++) {
        nonce_bytes[index] = (uint8_t)value;
        value >>= 8;
    }
    if (aad_len > 0) {
        (*env)->GetByteArrayRegion(env, aad, 0, aad_len, (jbyte *)aad_stack);
        if ((*env)->ExceptionCheck(env)) return -1;
    }
    pthread_mutex_lock(&frame_mu);
    if (ensure_frame_scratch((size_t)sealed_length) != 0) {
        pthread_mutex_unlock(&frame_mu);
        return -1;
    }
    (*env)->GetByteArrayRegion(env, sealed, 0, sealed_length, (jbyte *)frame_scratch);
    if ((*env)->ExceptionCheck(env)) {
        pthread_mutex_unlock(&frame_mu);
        return -1;
    }
    int opened = aead_open(key_bytes, nonce_bytes, aad_len > 0 ? aad_stack : NULL, (size_t)aad_len,
                           frame_scratch, (size_t)sealed_length, plain_len == 0 ? NULL : frame_scratch);
    if (opened == 0 && plain_len != 0) {
        (*env)->SetByteArrayRegion(env, destination, 0, plain_len, (const jbyte *)frame_scratch);
    }
    int failed = opened != 0 || (*env)->ExceptionCheck(env);
    pthread_mutex_unlock(&frame_mu);
    return failed ? -2 : plain_len;
}

JNIEXPORT jint JNICALL
Java_com_shihab_diplay_airplay_NativeChaCha_nativeOpenDirect(
        JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce, jbyteArray aad,
        jobject input, jint input_length, jobject output) {
    (void)clazz;
    return open_direct(env, key, nonce, aad, input, input_length, output);
}
