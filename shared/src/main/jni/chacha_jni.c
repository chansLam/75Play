#include <jni.h>
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
Java_com_shihab_diplay_airplay_NativeChaCha_nativeOpenDirect(
        JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce, jbyteArray aad,
        jobject input, jint input_length, jobject output) {
    (void)clazz;
    return open_direct(env, key, nonce, aad, input, input_length, output);
}
