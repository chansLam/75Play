#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <opus.h>

/*
 * Software Opus decoder, compiled into the same opus_microphone shared library
 * as the microphone encoder. One raw Opus access unit becomes PCM16LE.
 * 48 kHz, 1 or 2 channels, decoded at up to 2880 samples/frame (60 ms).
 * Needed because KitKat (API 19) has no MediaCodec Opus decoder: without this
 * fallback, telephony and navigation downlinks (which iOS insists on sending
 * as OPUS) are dropped and calls/navigation are silent.
 */

enum { MAX_FRAME_SAMPLES = 2880, MAX_CHANNELS = 2 };

static OpusDecoder *decoder_from_handle(jlong handle) {
    return (OpusDecoder *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL
Java_com_shihab_diplay_media_NativeOpusDecoder_nativeCreate(
        JNIEnv *env, jclass clazz, jint sampleRate, jint channels) {
    (void)env;
    (void)clazz;
    if (sampleRate <= 0 || channels < 1 || channels > MAX_CHANNELS) return 0;

    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(sampleRate, channels, &error);
    if (decoder == NULL || error != OPUS_OK) {
        if (decoder != NULL) opus_decoder_destroy(decoder);
        return 0;
    }
    return (jlong)(intptr_t)decoder;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shihab_diplay_media_NativeOpusDecoder_nativeDecode(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray packet,
        jint offset, jint length) {
    (void)clazz;
    OpusDecoder *decoder = decoder_from_handle(handle);
    if (decoder == NULL || packet == NULL) return NULL;

    jsize capacity = (*env)->GetArrayLength(env, packet);
    if (offset < 0 || length <= 0 || offset > capacity || length > capacity - offset) {
        return NULL;
    }
    jbyte *data = (*env)->GetByteArrayElements(env, packet, NULL);
    if (data == NULL) return NULL;

    jshort pcm[MAX_FRAME_SAMPLES * MAX_CHANNELS];
    int samples = opus_decode(decoder, (const unsigned char *)(data + offset), length,
                              pcm, MAX_FRAME_SAMPLES, 0);
    (*env)->ReleaseByteArrayElements(env, packet, data, JNI_ABORT);
    if (samples < 0) return NULL;

    int bytes = samples * (int)sizeof(jshort);
    jbyteArray out = (*env)->NewByteArray(env, bytes);
    if (out == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, out, 0, bytes, (const jbyte *)pcm);
    return out;
}

JNIEXPORT void JNICALL
Java_com_shihab_diplay_media_NativeOpusDecoder_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    opus_decoder_destroy(decoder_from_handle(handle));
}
