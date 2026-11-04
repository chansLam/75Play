// NCM-only asynchronous usbfs data path. It uses the framework-authorized fd and never opens,
// resets or reconfigures the device.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>

#define TAG "xcertplay-usbfs-ncm"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define PHASE_IDLE 0
#define PHASE_SUBMITTED 1
#define PHASE_CANCELLING 2
#define NO_COMPLETION_MS 10000
#define RECLAIM_MS 2000
#define RX_QUEUE_CAP 16
#define NTH16_SIG 0x484d434eU
#define NDP16_SIG 0x304d434eU
#define NTH16_LENGTH 12
#define MIN_NTB16_LENGTH 28

typedef struct RxChunk {
    unsigned char *data;
    int len;
} RxChunk;

typedef struct NcmUrbSlot {
    struct usbdevfs_urb urb;
    unsigned char *buffer;
    int capacity;
    int completed;
    int in_flight;
    int is_in;
    int phase;
    int no_completion_latched;
    int reclaim_latched;
    int64_t submitted_ms;
    int64_t cancel_ms;
} NcmUrbSlot;

typedef struct NcmIo {
    int fd;
    int depth;
    int out_endpoint;
    int aborted;
    pthread_mutex_t lock;
    NcmUrbSlot *in_slots;
    NcmUrbSlot *out_slots;
    uint32_t submitted_in;
    uint32_t completed_in;
    uint32_t submitted_out;
    uint32_t completed_out;
    uint32_t discarded;
    uint32_t stall_out;
    uint32_t nak_out;
    uint32_t no_completion;
    uint32_t reclaim_failed;
    uint32_t success_bytes;
    int latency_ms;
    RxChunk rx_q[RX_QUEUE_CAP];
    int rx_head;
    int rx_count;
    unsigned char *ntb_buffer;
    int ntb_head;
    int ntb_size;
    int ntb_capacity;
    uint16_t out_sequence;
    void *lwip;
    int (*lwip_input)(void *, const unsigned char *, int);
    uint32_t direct_frames;
} NcmIo;

static __thread int last_open_errno = 0;

static int64_t mono_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void init_urb(NcmUrbSlot *slot, int endpoint, int capacity, int is_in) {
    memset(slot, 0, sizeof(*slot));
    slot->buffer = (unsigned char *)malloc((size_t)capacity);
    slot->capacity = capacity;
    slot->is_in = is_in;
    slot->urb.type = USBDEVFS_URB_TYPE_BULK;
    slot->urb.endpoint = (unsigned char)endpoint;
    slot->urb.buffer = slot->buffer;
    slot->urb.buffer_length = capacity;
    slot->urb.usercontext = slot;
}

static NcmUrbSlot *take_out_slot(NcmIo *io) {
    NcmUrbSlot *idle = NULL;
    for (int i = 0; i < io->depth; i++) {
        NcmUrbSlot *slot = &io->out_slots[i];
        if (slot->in_flight) continue;
        if (idle == NULL || slot->capacity > idle->capacity) idle = slot;
    }
    return idle;
}

static int ensure_out_buffer(NcmUrbSlot *slot, int endpoint, int length) {
    if (length <= 0 || length > 65535) return 0;
    if (slot->buffer == NULL || slot->capacity < length) {
        unsigned char *grown = (unsigned char *)realloc(slot->buffer, (size_t)length);
        if (grown == NULL) return 0;
        slot->buffer = grown;
        slot->capacity = length;
    }
    slot->urb.type = USBDEVFS_URB_TYPE_BULK;
    slot->urb.endpoint = (unsigned char)endpoint;
    slot->urb.buffer = slot->buffer;
    slot->urb.usercontext = slot;
    slot->is_in = 0;
    slot->completed = 0;
    slot->no_completion_latched = 0;
    slot->reclaim_latched = 0;
    return 1;
}

static int submit_slot(NcmIo *io, NcmUrbSlot *slot, int length) {
    if (slot->in_flight || slot->buffer == NULL || io->aborted) return 0;
    slot->urb.actual_length = 0;
    slot->urb.status = 0;
    slot->urb.buffer_length = length;
    if (ioctl(io->fd, USBDEVFS_SUBMITURB, &slot->urb) != 0) return 0;
    slot->in_flight = 1;
    slot->completed = 0;
    slot->phase = PHASE_SUBMITTED;
    slot->submitted_ms = mono_ms();
    if (slot->is_in) io->submitted_in++; else io->submitted_out++;
    return 1;
}

static void note_out_status(NcmIo *io, NcmUrbSlot *slot, int status) {
    if (status == 0) {
        io->completed_out++;
        io->success_bytes += (uint32_t)slot->urb.actual_length;
        int latency = (int)(mono_ms() - slot->submitted_ms);
        if (latency > io->latency_ms) io->latency_ms = latency;
    } else if (status == -EPIPE) {
        io->stall_out++;
    } else if (status == -ETIMEDOUT || status == -EAGAIN) {
        io->nak_out++;
    }
    slot->phase = PHASE_IDLE;
    slot->in_flight = 0;
}

static int append_ntb_chunk(NcmIo *io, const unsigned char *data, int length);
static void discard_ntb_prefix(NcmIo *io, int count);

/* Completed IN data is appended straight into the NTB buffer. The URB can then be
 * resubmitted without a per-packet malloc. A full reassembly buffer leaves the URB
 * completed so the next receive retries instead of dropping the chunk. */
static int enqueue_rx(NcmIo *io, const unsigned char *data, int len) {
    if (len <= 0) return 0;
    return append_ntb_chunk(io, data, len);
}

static void recycle_in_slot(NcmIo *io, NcmUrbSlot *slot) {
    if (slot->in_flight || slot->buffer == NULL) return;
    if (slot->completed) {
        if (slot->urb.status == 0 && slot->urb.actual_length > 0 &&
            !enqueue_rx(io, slot->buffer, slot->urb.actual_length)) {
            return;
        }
        slot->completed = 0;
    }
    if (!io->aborted) (void)submit_slot(io, slot, slot->capacity);
}

static void reap_ready(NcmIo *io) {
    while (1) {
        struct usbdevfs_urb *urb = NULL;
        if (ioctl(io->fd, USBDEVFS_REAPURBNDELAY, &urb) != 0) {
            if (errno != EAGAIN && errno != ENODEV) LOGW("REAPURBNDELAY errno=%d", errno);
            break;
        }
        if (urb == NULL || urb->usercontext == NULL) continue;
        NcmUrbSlot *slot = (NcmUrbSlot *)urb->usercontext;
        if (slot->is_in) {
            slot->in_flight = 0;
            slot->phase = PHASE_IDLE;
            io->completed_in++;
            slot->completed = (urb->status == 0 && urb->actual_length > 0);
            recycle_in_slot(io, slot);
        } else {
            note_out_status(io, slot, urb->status);
        }
    }
    for (int i = 0; i < io->depth; i++) recycle_in_slot(io, &io->in_slots[i]);
}

static void observe_out_ages(NcmIo *io) {
    int64_t now = mono_ms();
    for (int i = 0; i < io->depth; i++) {
        NcmUrbSlot *slot = &io->out_slots[i];
        if (slot->in_flight && slot->phase == PHASE_SUBMITTED && !slot->no_completion_latched &&
            now - slot->submitted_ms > NO_COMPLETION_MS) {
            slot->no_completion_latched = 1;
            io->no_completion++;
        }
        if (slot->in_flight && slot->phase == PHASE_CANCELLING && !slot->reclaim_latched &&
            now - slot->cancel_ms > RECLAIM_MS) {
            slot->reclaim_latched = 1;
            io->reclaim_failed++;
        }
    }
}

static void discard_all(NcmIo *io) {
    io->aborted = 1;
    for (int i = 0; i < io->depth; i++) {
        NcmUrbSlot *slots[] = { &io->in_slots[i], &io->out_slots[i] };
        for (int n = 0; n < 2; n++) {
            if (slots[n]->in_flight) {
                slots[n]->phase = PHASE_CANCELLING;
                slots[n]->cancel_ms = mono_ms();
                (void)ioctl(io->fd, USBDEVFS_DISCARDURB, &slots[n]->urb);
                io->discarded++;
            }
        }
    }
}

static void free_rx_queue(NcmIo *io) {
    for (int i = 0; i < io->rx_count; i++) {
        free(io->rx_q[(io->rx_head + i) % RX_QUEUE_CAP].data);
    }
    io->rx_count = 0;
}

static void free_if_reaped(NcmIo *io) {
    reap_ready(io);
    free_rx_queue(io);
    free(io->ntb_buffer);
    io->ntb_buffer = NULL;
    for (int i = 0; i < io->depth; i++) {
        if (io->in_slots[i].in_flight || io->out_slots[i].in_flight) {
            // Some KitKat kernels never reap a discarded bulk URB. Leaking this small context is
            // safer than freeing memory the kernel could still DMA into.
            LOGW("leaking NCM io context with an unreaped URB");
            return;
        }
    }
    for (int i = 0; i < io->depth; i++) {
        free(io->in_slots[i].buffer);
        free(io->out_slots[i].buffer);
    }
    free(io->in_slots);
    free(io->out_slots);
    pthread_mutex_destroy(&io->lock);
    free(io);
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeOpen(
        JNIEnv *env, jclass clazz, jint fd, jint in_endpoint, jint out_endpoint,
        jint read_bytes, jint depth) {
    (void)env; (void)clazz;
    last_open_errno = 0;
    if (fd < 0 || read_bytes < 512 || depth < 1 || depth > 16) {
        last_open_errno = EINVAL;
        return 0;
    }
    NcmIo *io = (NcmIo *)calloc(1, sizeof(NcmIo));
    if (io == NULL) {
        last_open_errno = ENOMEM;
        return 0;
    }
    io->fd = fd;
    io->depth = depth;
    io->out_endpoint = out_endpoint;
    pthread_mutex_init(&io->lock, NULL);
    io->in_slots = (NcmUrbSlot *)calloc((size_t)depth, sizeof(NcmUrbSlot));
    io->out_slots = (NcmUrbSlot *)calloc((size_t)depth, sizeof(NcmUrbSlot));
    if (io->in_slots == NULL || io->out_slots == NULL) {
        last_open_errno = ENOMEM;
        free(io->in_slots); free(io->out_slots);
        pthread_mutex_destroy(&io->lock);
        free(io);
        return 0;
    }
    pthread_mutex_lock(&io->lock);
    for (int i = 0; i < depth; i++) {
        init_urb(&io->in_slots[i], in_endpoint, read_bytes, 1);
        if (io->in_slots[i].buffer == NULL) last_open_errno = ENOMEM;
        if (io->in_slots[i].buffer == NULL || !submit_slot(io, &io->in_slots[i], read_bytes)) {
            if (last_open_errno == 0) last_open_errno = errno;
            discard_all(io);
            pthread_mutex_unlock(&io->lock);
            free_if_reaped(io);
            return 0;
        }
    }
    pthread_mutex_unlock(&io->lock);
    return (jlong)(intptr_t)io;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeLastOpenErrno(
        JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return last_open_errno;
}

JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeSubmitOut(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray block) {
    (void)clazz;
    NcmIo *io = (NcmIo *)(intptr_t)handle;
    if (io == NULL || block == NULL) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, block);
    if (length <= 0 || length > 65535) return JNI_FALSE;
    pthread_mutex_lock(&io->lock);
    reap_ready(io);
    if (io->aborted) { pthread_mutex_unlock(&io->lock); return JNI_FALSE; }
    NcmUrbSlot *slot = take_out_slot(io);
    if (slot != NULL && ensure_out_buffer(slot, io->out_endpoint, length)) {
        (*env)->GetByteArrayRegion(env, block, 0, length, (jbyte *)slot->buffer);
        if (!(*env)->ExceptionCheck(env) && submit_slot(io, slot, length)) {
            pthread_mutex_unlock(&io->lock);
            return JNI_TRUE;
        }
    }
    pthread_mutex_unlock(&io->lock);
    return JNI_FALSE;
}

static int ntb_align(int value, int alignment) {
    return (value + alignment - 1) & -alignment;
}

static void ntb_put_u16(unsigned char *target, int offset, int value) {
    target[offset] = (unsigned char)value;
    target[offset + 1] = (unsigned char)(value >> 8);
}

static void ntb_put_u32(unsigned char *target, int offset, uint32_t value) {
    target[offset] = (unsigned char)value;
    target[offset + 1] = (unsigned char)(value >> 8);
    target[offset + 2] = (unsigned char)(value >> 16);
    target[offset + 3] = (unsigned char)(value >> 24);
}

/*
 * Build one negotiated NTB16 directly in an OUT URB buffer. This is intentionally the only
 * Java-to-native copy on the API 19 async path: there is no intermediate managed NTB array.
 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeSubmitFrames(
        JNIEnv *env, jclass clazz, jlong handle, jobjectArray frames, jint sequence,
        jint max_block_bytes, jint alignment) {
    (void)clazz;
    NcmIo *io = (NcmIo *)(intptr_t)handle;
    if (io == NULL || frames == NULL || sequence < 0 || sequence > 65535 ||
        max_block_bytes < 64 || max_block_bytes > 65535 || alignment <= 0 ||
        (alignment & (alignment - 1)) != 0) return 0;
    jsize count = (*env)->GetArrayLength(env, frames);
    if (count < 1 || count > 32) return 0;

    int offsets[32];
    int lengths[32];
    int ndp_length = 8 + ((int)count + 1) * 4;
    int cursor = ntb_align(12 + ndp_length, alignment);
    for (jsize i = 0; i < count; i++) {
        jbyteArray frame = (jbyteArray)(*env)->GetObjectArrayElement(env, frames, i);
        if (frame == NULL) return 0;
        int length = (*env)->GetArrayLength(env, frame);
        (*env)->DeleteLocalRef(env, frame);
        if (length <= 0 || length > 65535) return 0;
        cursor = ntb_align(cursor, alignment);
        if (cursor > 65535 - length) return 0;
        offsets[i] = cursor;
        lengths[i] = length;
        cursor += length;
    }
    int block_length = ntb_align(cursor, alignment);
    if (block_length > max_block_bytes || block_length > 65535 || ndp_length > 65535) return 0;
    int transfer_length = block_length % 512 == 0 ? block_length + 1 : block_length;

    pthread_mutex_lock(&io->lock);
    reap_ready(io);
    if (io->aborted) {
        pthread_mutex_unlock(&io->lock);
        return 0;
    }
    NcmUrbSlot *slot = take_out_slot(io);
    if (slot == NULL || !ensure_out_buffer(slot, io->out_endpoint, transfer_length)) {
        pthread_mutex_unlock(&io->lock);
        return 0;
    }
    memset(slot->buffer, 0, (size_t)transfer_length);
    ntb_put_u32(slot->buffer, 0, 0x484d434eU);
    ntb_put_u16(slot->buffer, 4, 12);
    ntb_put_u16(slot->buffer, 6, io->out_sequence);
    io->out_sequence++;
    ntb_put_u16(slot->buffer, 8, block_length);
    ntb_put_u16(slot->buffer, 10, 12);
    ntb_put_u32(slot->buffer, 12, 0x304d434eU);
    ntb_put_u16(slot->buffer, 16, ndp_length);
    for (jsize i = 0; i < count; i++) {
        jbyteArray frame = (jbyteArray)(*env)->GetObjectArrayElement(env, frames, i);
        if (frame == NULL) {
            pthread_mutex_unlock(&io->lock);
            return 0;
        }
        (*env)->GetByteArrayRegion(env, frame, 0, lengths[i], (jbyte *)(slot->buffer + offsets[i]));
        (*env)->DeleteLocalRef(env, frame);
        if ((*env)->ExceptionCheck(env)) {
            pthread_mutex_unlock(&io->lock);
            return 0;
        }
        int entry = 20 + (int)i * 4;
        ntb_put_u16(slot->buffer, entry, offsets[i]);
        ntb_put_u16(slot->buffer, entry + 2, lengths[i]);
    }
    if (submit_slot(io, slot, transfer_length)) {
        pthread_mutex_unlock(&io->lock);
        return transfer_length;
    }
    pthread_mutex_unlock(&io->lock);
    return 0;
}

static int ntb_block_length(const int *lengths, int count, int alignment) {
    int ndp_length = 8 + (count + 1) * 4;
    int cursor = ntb_align(12 + ndp_length, alignment);
    for (int i = 0; i < count; i++) {
        if (lengths[i] <= 0 || lengths[i] > 65535) return -1;
        cursor = ntb_align(cursor, alignment);
        if (cursor > 65535 - lengths[i]) return -1;
        cursor += lengths[i];
    }
    return ntb_align(cursor, alignment);
}

/*
 * Called by lwIP's transmit thread in the other native library. The NcmIo pointer remains owned
 * by usbfs, so this is the only URB submitter besides the JNI entry points above.
 * Returns the submitted byte count, 0 when every OUT slot is busy, or -1 when the first frame
 * cannot fit. *accepted receives the prefix that was packed.
 */
int ncm_usbfs_submit_ethernet(
        void *handle, const unsigned char *const *frames, const int *lengths, int count,
        int max_block_bytes, int alignment, int *accepted) {
    if (accepted != NULL) *accepted = 0;
    NcmIo *io = (NcmIo *)handle;
    if (io == NULL || frames == NULL || lengths == NULL || count < 1 ||
        max_block_bytes < 64 || max_block_bytes > 65535 || alignment <= 0 ||
        (alignment & (alignment - 1)) != 0) return -1;
    if (count > 32) count = 32;
    int packed = 0;
    for (int candidate = 1; candidate <= count; candidate++) {
        int length = ntb_block_length(lengths, candidate, alignment);
        if (length < 0 || length > max_block_bytes) break;
        packed = candidate;
    }
    if (packed == 0) return -1;

    int offsets[32];
    int ndp_length = 8 + (packed + 1) * 4;
    int cursor = ntb_align(12 + ndp_length, alignment);
    for (int i = 0; i < packed; i++) {
        cursor = ntb_align(cursor, alignment);
        offsets[i] = cursor;
        cursor += lengths[i];
    }
    int block_length = ntb_align(cursor, alignment);
    int transfer_length = block_length % 512 == 0 ? block_length + 1 : block_length;

    pthread_mutex_lock(&io->lock);
    reap_ready(io);
    if (io->aborted) {
        pthread_mutex_unlock(&io->lock);
        return 0;
    }
    NcmUrbSlot *slot = take_out_slot(io);
    if (slot == NULL || !ensure_out_buffer(slot, io->out_endpoint, transfer_length)) {
        pthread_mutex_unlock(&io->lock);
        return 0;
    }
    memset(slot->buffer, 0, (size_t)transfer_length);
    ntb_put_u32(slot->buffer, 0, 0x484d434eU);
    ntb_put_u16(slot->buffer, 4, 12);
    ntb_put_u16(slot->buffer, 6, io->out_sequence);
    io->out_sequence++;
    ntb_put_u16(slot->buffer, 8, block_length);
    ntb_put_u16(slot->buffer, 10, 12);
    ntb_put_u32(slot->buffer, 12, 0x304d434eU);
    ntb_put_u16(slot->buffer, 16, ndp_length);
    for (int i = 0; i < packed; i++) {
        memcpy(slot->buffer + offsets[i], frames[i], (size_t)lengths[i]);
        int entry = 20 + i * 4;
        ntb_put_u16(slot->buffer, entry, offsets[i]);
        ntb_put_u16(slot->buffer, entry + 2, lengths[i]);
    }
    if (!submit_slot(io, slot, transfer_length)) {
        pthread_mutex_unlock(&io->lock);
        return 0;
    }
    pthread_mutex_unlock(&io->lock);
    if (accepted != NULL) *accepted = packed;
    return transfer_length;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeReceive(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray target, jint timeout_ms) {
    (void)clazz;
    NcmIo *io = (NcmIo *)(intptr_t)handle;
    if (io == NULL || target == NULL || timeout_ms < 0) return -1;
    pthread_mutex_lock(&io->lock);
    reap_ready(io);
    if (io->ntb_size == 0 && !io->aborted) {
        pthread_mutex_unlock(&io->lock);
        struct pollfd pfd = { .fd = io->fd, .events = POLLIN, .revents = 0 };
        (void)poll(&pfd, 1, timeout_ms);
        pthread_mutex_lock(&io->lock);
        reap_ready(io);
    }
    if (io->ntb_size <= 0) {
        pthread_mutex_unlock(&io->lock);
        return 0;
    }
    jsize max = (*env)->GetArrayLength(env, target);
    int count = io->ntb_size;
    if (count > max) count = max;
    if (count > 0) {
        (*env)->SetByteArrayRegion(env, target, 0, count, (const jbyte *)(io->ntb_buffer + io->ntb_head));
    }
    if (!(*env)->ExceptionCheck(env) && count > 0) discard_ntb_prefix(io, count);
    pthread_mutex_unlock(&io->lock);
    return (*env)->ExceptionCheck(env) ? -1 : count;
}

static uint16_t ntb_u16(const unsigned char *source, int offset) {
    return (uint16_t)source[offset] | ((uint16_t)source[offset + 1] << 8);
}

static uint32_t ntb_u32(const unsigned char *source, int offset) {
    return (uint32_t)source[offset] | ((uint32_t)source[offset + 1] << 8) |
           ((uint32_t)source[offset + 2] << 16) | ((uint32_t)source[offset + 3] << 24);
}

static void ntb_compact(NcmIo *io) {
    if (io->ntb_head == 0) return;
    if (io->ntb_size > 0) {
        memmove(io->ntb_buffer, io->ntb_buffer + io->ntb_head, (size_t)io->ntb_size);
    }
    io->ntb_head = 0;
}

static int append_ntb_chunk(NcmIo *io, const unsigned char *data, int length) {
    if (length <= 0) return 1;
    if (io->ntb_size > 65535 || length > 65535 - io->ntb_size) return 0;
    int required = io->ntb_size + length;
    if (required > io->ntb_capacity) {
        ntb_compact(io);
        int capacity = io->ntb_capacity == 0 ? 32768 : io->ntb_capacity;
        while (capacity < required) capacity *= 2;
        unsigned char *grown = (unsigned char *)realloc(io->ntb_buffer, (size_t)capacity);
        if (grown == NULL) return 0;
        io->ntb_buffer = grown;
        io->ntb_capacity = capacity;
    }
    if (io->ntb_head != 0 && io->ntb_head + required > io->ntb_capacity) ntb_compact(io);
    memcpy(io->ntb_buffer + io->ntb_head + io->ntb_size, data, (size_t)length);
    io->ntb_size += length;
    return 1;
}

static void discard_ntb_prefix(NcmIo *io, int count) {
    if (count <= 0) return;
    if (count >= io->ntb_size) {
        io->ntb_size = 0;
        io->ntb_head = 0;
        return;
    }
    io->ntb_head += count;
    io->ntb_size -= count;
}

static void drain_rx_chunks_into_ntb(NcmIo *io) {
    while (io->rx_count > 0) {
        RxChunk chunk = io->rx_q[io->rx_head];
        io->rx_q[io->rx_head].data = NULL;
        io->rx_head = (io->rx_head + 1) % RX_QUEUE_CAP;
        io->rx_count--;
        if (!append_ntb_chunk(io, chunk.data, chunk.len)) {
            io->ntb_size = 0;
            io->ntb_head = 0;
            LOGW("NCM NTB reassembly capacity exceeded");
        }
        free(chunk.data);
    }
}

/* Finds a plausible NTB16 header after transport damage without exposing raw chunks to Dalvik. */
static int resync_ntb(NcmIo *io) {
    const unsigned char *base = io->ntb_buffer + io->ntb_head;
    for (int offset = 1; offset + NTH16_LENGTH <= io->ntb_size; offset++) {
        if (ntb_u32(base, offset) != NTH16_SIG) continue;
        int header = ntb_u16(base, offset + 4);
        int length = ntb_u16(base, offset + 8);
        int ndp = ntb_u16(base, offset + 10);
        if (header == NTH16_LENGTH && length >= MIN_NTB16_LENGTH &&
            ndp >= NTH16_LENGTH && ndp + 8 <= length) {
            discard_ntb_prefix(io, offset);
            return 1;
        }
    }
    if (io->ntb_size > NTH16_LENGTH - 1) discard_ntb_prefix(io, io->ntb_size - (NTH16_LENGTH - 1));
    return 0;
}

/* ICMPv6 and mDNS stay in Java. Other IPv6 frames can enter lwIP without another managed copy. */
static int ncm_frame_needs_java(const unsigned char *frame, int length) {
    if (length < 54 || frame[12] != 0x86 || frame[13] != 0xdd) return 1;
    if (frame[20] == 58) return 1;
    if (frame[20] == 17 && length >= 58) {
        int source = ((int)frame[54] << 8) | frame[55];
        int destination = ((int)frame[56] << 8) | frame[57];
        if (source == 5353 || destination == 5353) return 1;
    }
    return 0;
}

JNIEXPORT jobjectArray JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeReceiveFrames(
        JNIEnv *env, jclass clazz, jlong handle, jint timeout_ms) {
    (void)clazz;
    NcmIo *io = (NcmIo *)(intptr_t)handle;
    if (io == NULL || timeout_ms < 0) return NULL;
    pthread_mutex_lock(&io->lock);
    reap_ready(io);
    drain_rx_chunks_into_ntb(io);
    if (io->ntb_size < NTH16_LENGTH && !io->aborted) {
        pthread_mutex_unlock(&io->lock);
        struct pollfd pfd = { .fd = io->fd, .events = POLLIN, .revents = 0 };
        (void)poll(&pfd, 1, timeout_ms);
        pthread_mutex_lock(&io->lock);
        reap_ready(io);
        drain_rx_chunks_into_ntb(io);
    }
    if (io->ntb_size < NTH16_LENGTH) {
        pthread_mutex_unlock(&io->lock);
        return NULL;
    }
    const unsigned char *block = io->ntb_buffer + io->ntb_head;
    if (ntb_u32(block, 0) != NTH16_SIG || ntb_u16(block, 4) != NTH16_LENGTH) {
        (void)resync_ntb(io);
        pthread_mutex_unlock(&io->lock);
        return NULL;
    }
    int block_length = ntb_u16(block, 8);
    int ndp_offset = ntb_u16(block, 10);
    if (block_length < MIN_NTB16_LENGTH || ndp_offset < NTH16_LENGTH || ndp_offset + 8 > block_length) {
        discard_ntb_prefix(io, 1);
        pthread_mutex_unlock(&io->lock);
        return NULL;
    }
    if (io->ntb_size < block_length) {
        pthread_mutex_unlock(&io->lock);
        return NULL;
    }
    int frame_offsets[32];
    int frame_lengths[32];
    int frame_count = 0;
    int hops = 0;
    while (ndp_offset != 0 && hops++ < block_length / 4 && ndp_offset + 8 <= block_length) {
        if ((ntb_u32(block, ndp_offset) & 0x00ffffffU) != (NDP16_SIG & 0x00ffffffU)) break;
        int ndp_length = ntb_u16(block, ndp_offset + 4);
        int next_ndp = ntb_u16(block, ndp_offset + 6);
        int ndp_end = ndp_offset + ndp_length;
        if (ndp_length < 12 || ndp_end > block_length) break;
        for (int entry = ndp_offset + 8; entry + 4 <= ndp_end && frame_count < 32; entry += 4) {
            int offset = ntb_u16(block, entry);
            int length = ntb_u16(block, entry + 2);
            if (offset == 0 || length == 0) break;
            if (offset >= NTH16_LENGTH && offset <= block_length && length <= block_length - offset) {
                frame_offsets[frame_count] = offset;
                frame_lengths[frame_count++] = length;
            }
        }
        ndp_offset = next_ndp;
    }
    int java_index[32];
    int java_count = 0;
    int direct_index[32];
    int direct_count = 0;
    void *lwip = io->lwip;
    int (*lwip_input)(void *, const unsigned char *, int) = io->lwip_input;
    for (int i = 0; i < frame_count; i++) {
        const unsigned char *frame = block + frame_offsets[i];
        int length = frame_lengths[i];
        int to_java = lwip_input == NULL || ncm_frame_needs_java(frame, length);
        if (!to_java && length >= 14 && frame[12] == 0x86 && frame[13] == 0xdd) {
            direct_index[direct_count++] = i;
            continue;
        }
        if (lwip_input != NULL && length >= 14 && (frame[12] != 0x86 || frame[13] != 0xdd)) continue;
        java_index[java_count++] = i;
    }
    if (direct_count > 0) io->direct_frames += (uint32_t)direct_count;
    jobjectArray result = NULL;
    if (java_count > 0) {
        jclass byte_array_class = (*env)->FindClass(env, "[B");
        if (byte_array_class != NULL) {
            result = (*env)->NewObjectArray(env, java_count, byte_array_class, NULL);
            (*env)->DeleteLocalRef(env, byte_array_class);
            if (result != NULL) {
                for (int i = 0; i < java_count; i++) {
                    int index = java_index[i];
                    jbyteArray frame = (*env)->NewByteArray(env, frame_lengths[index]);
                    if (frame == NULL) break;
                    (*env)->SetByteArrayRegion(env, frame, 0, frame_lengths[index],
                                               (const jbyte *)(block + frame_offsets[index]));
                    (*env)->SetObjectArrayElement(env, result, i, frame);
                    (*env)->DeleteLocalRef(env, frame);
                    if ((*env)->ExceptionCheck(env)) break;
                }
            }
        }
    }
    pthread_mutex_unlock(&io->lock);
    /* The receive thread is the only mutator of the reassembly buffer, so these bytes stay
     * stable until the discard below. lwIP must not run while the usbfs lock is held. */
    for (int i = 0; i < direct_count; i++) {
        int index = direct_index[i];
        if (lwip_input != NULL) {
            (void)lwip_input(lwip, block + frame_offsets[index], frame_lengths[index]);
        }
    }
    pthread_mutex_lock(&io->lock);
    discard_ntb_prefix(io, block_length);
    if (io->ntb_size > 0 && io->ntb_buffer[io->ntb_head] == 0) discard_ntb_prefix(io, 1);
    pthread_mutex_unlock(&io->lock);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeTakeDirectFrames(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NcmIo *io = (NcmIo *)(intptr_t)handle;
    if (io == NULL) return 0;
    pthread_mutex_lock(&io->lock);
    uint32_t count = io->direct_frames;
    io->direct_frames = 0;
    pthread_mutex_unlock(&io->lock);
    return count > 0x7fffffffU ? 0x7fffffff : (jint)count;
}

JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeBindLwip(
        JNIEnv *env, jclass clazz, jlong handle, jlong lwip_handle) {
    (void)env; (void)clazz;
    NcmIo *io = (NcmIo *)(intptr_t)handle;
    if (io == NULL || lwip_handle == 0) return JNI_FALSE;
    int (*input)(void *, const unsigned char *, int) =
        (int (*)(void *, const unsigned char *, int))dlsym(RTLD_DEFAULT, "ncm_lwip_input_ethernet");
    if (input == NULL) return JNI_FALSE;
    pthread_mutex_lock(&io->lock);
    io->lwip = (void *)(intptr_t)lwip_handle;
    io->lwip_input = input;
    pthread_mutex_unlock(&io->lock);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeAvailableOutSlots(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NcmIo *io = (NcmIo *)(intptr_t)handle;
    if (io == NULL) return 0;
    pthread_mutex_lock(&io->lock);
    reap_ready(io);
    int free_slots = 0;
    for (int i = 0; i < io->depth; i++) if (!io->out_slots[i].in_flight) free_slots++;
    pthread_mutex_unlock(&io->lock);
    return free_slots;
}

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeDiagnostics(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    NcmIo *io = (NcmIo *)(intptr_t)handle;
    char line[320];
    if (io == NULL) return (*env)->NewStringUTF(env, "unavailable");
    pthread_mutex_lock(&io->lock);
    reap_ready(io);
    observe_out_ages(io);
    int queued = 0;
    int cancelling = 0;
    for (int i = 0; i < io->depth; i++) {
        if (io->out_slots[i].buffer != NULL && !io->out_slots[i].in_flight) queued++;
        if (io->out_slots[i].in_flight && io->out_slots[i].phase == PHASE_CANCELLING) cancelling++;
    }
    snprintf(line, sizeof(line),
             "queued=%d submitted=%u completed=%u cancelling=%d reclaimFailed=%u stall=%u nak=%u "
             "noCompletion=%u latencyMs=%d replug=%d successBytes=%u discarded=%u",
             queued, io->submitted_out, io->completed_out, cancelling, io->reclaim_failed,
             io->stall_out, io->nak_out, io->no_completion, io->latency_ms,
             io->reclaim_failed > 0 ? 1 : 0, io->success_bytes, io->discarded);
    pthread_mutex_unlock(&io->lock);
    return (*env)->NewStringUTF(env, line);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeAbort(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NcmIo *io = (NcmIo *)(intptr_t)handle;
    if (io == NULL) return;
    pthread_mutex_lock(&io->lock);
    discard_all(io);
    pthread_mutex_unlock(&io->lock);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_transport_UsbfsNcmIo_nativeClose(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NcmIo *io = (NcmIo *)(intptr_t)handle;
    if (io == NULL) return;
    pthread_mutex_lock(&io->lock);
    discard_all(io);
    pthread_mutex_unlock(&io->lock);
    free_if_reaped(io);
}
