#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/usbdevice_fs.h>

/* usb_host_compat
 *
 * Raw usbfs fallback for Android 9 and older head units where the Android framework
 * cannot select a USB device's active configuration by itself (on API 19,
 * UsbDeviceConnection.setConfiguration() is not a public callable). Each JNI function
 * returns 0 on success or the positive errno on failure, so Kotlin
 * (LegacyUsbHostCompat) can apply its EBUSY(16) / EPROTO(71) handling without
 * parsing -1.
 *
 * Ported from the DiPlay-Legacy-Android upstream (df520d42, v0.3.10); the
 * LegacyTunBlocking setBlocking helper is omitted because this branch never migrated
 * that class. Package / JNI names match com.shilapi.xcertplay.transport.LegacyUsbHostNative.
 */

/* Returns 0 when the interface is claimed, otherwise the final errno. */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_forceClaim(
        JNIEnv *env, jobject self, jint file_descriptor, jint interface_id) {
    (void) env;
    (void) self;

    if (file_descriptor < 0 || interface_id < 0) return EINVAL;

#ifdef USBDEVFS_DISCONNECT_CLAIM
    struct usbdevfs_disconnect_claim disconnect_claim;
    memset(&disconnect_claim, 0, sizeof(disconnect_claim));
    disconnect_claim.interface = (unsigned int) interface_id;
    disconnect_claim.flags = 0;
    if (ioctl(file_descriptor, USBDEVFS_DISCONNECT_CLAIM, &disconnect_claim) == 0) {
        return 0;
    }
#endif

#ifdef USBDEVFS_DISCONNECT
    struct usbdevfs_ioctl disconnect;
    memset(&disconnect, 0, sizeof(disconnect));
    disconnect.ifno = interface_id;
    disconnect.ioctl_code = USBDEVFS_DISCONNECT;
    disconnect.data = NULL;
    (void) ioctl(file_descriptor, USBDEVFS_IOCTL, &disconnect);
#endif

    unsigned int iface = (unsigned int) interface_id;
    if (ioctl(file_descriptor, USBDEVFS_CLAIMINTERFACE, &iface) == 0) {
        return 0;
    }
    return errno != 0 ? errno : EIO;
}


JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_setInterface(
        JNIEnv *env, jobject self, jint file_descriptor, jint interface_id, jint alternate_setting) {
    (void) env;
    (void) self;

    if (file_descriptor < 0 || interface_id < 0 || alternate_setting < 0) return EINVAL;

    struct usbdevfs_setinterface set_interface;
    memset(&set_interface, 0, sizeof(set_interface));
    set_interface.interface = (unsigned int) interface_id;
    set_interface.altsetting = (unsigned int) alternate_setting;

    if (ioctl(file_descriptor, USBDEVFS_SETINTERFACE, &set_interface) == 0) {
        return 0;
    }
    return errno != 0 ? errno : EIO;
}

/* Returns 0 when the port reset completed, otherwise the final errno. A reset makes
 * the device re-enumerate exactly like a physical unplug/replug, unwedging iPhones
 * that stopped answering control transfers (usbfs errno EPROTO). */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_resetDevice(
        JNIEnv *env, jobject self, jint file_descriptor) {
    (void) env;
    (void) self;

    if (file_descriptor < 0) return EINVAL;

    if (ioctl(file_descriptor, USBDEVFS_RESET) == 0) {
        return 0;
    }
    return errno != 0 ? errno : EIO;
}

/* Issue #279 follow-up: bounce a data interface through alt=0 then back to targetAlt.
 *
 * Re-selecting the alternate setting the kernel is already on is a no-op on usbfs:
 * the ioctl short-circuits, so the host controller and the phone's NCM function never
 * rebuild the bulk pipe and a wedged OUT endpoint (bulk URB rejected immediately with
 * no halt bit set) stays dead. Toggling through alt=0 tears the endpoints down on both
 * sides and re-arms them at targetAlt, which resets the DATA0/DATA1 data toggle on the
 * pipe without a port reset (issue #243: port resets wedge these i.MX6 kernels).
 * Returns 0 when both SETINTERFACE calls succeeded, otherwise the first failing errno. */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_bounceInterface(
        JNIEnv *env, jobject self, jint file_descriptor, jint interface_id, jint target_alt) {
    (void) env;
    (void) self;

    if (file_descriptor < 0 || interface_id < 0 || interface_id > 255) return EINVAL;
    if (target_alt < 0 || target_alt > 255) return EINVAL;

    struct usbdevfs_setinterface bounce;
    memset(&bounce, 0, sizeof(bounce));
    bounce.interface = (unsigned int) interface_id;
    bounce.altsetting = 0;
    if (ioctl(file_descriptor, USBDEVFS_SETINTERFACE, &bounce) != 0) {
        return errno != 0 ? errno : EIO;
    }

    bounce.altsetting = (unsigned int) target_alt;
    if (ioctl(file_descriptor, USBDEVFS_SETINTERFACE, &bounce) != 0) {
        return errno != 0 ? errno : EIO;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_setConfiguration(
        JNIEnv *env, jobject self, jint file_descriptor, jint configuration_id) {
    (void) env;
    (void) self;

    if (file_descriptor < 0 || configuration_id < 0) return EINVAL;

    unsigned int configuration = (unsigned int) configuration_id;
    if (ioctl(file_descriptor, USBDEVFS_SETCONFIGURATION, &configuration) == 0) {
        return 0;
    }
    int result = errno != 0 ? errno : EIO;
    if (result != EBUSY) return result;

    /* SETCONFIGURATION refuses bound interfaces. Only detach kernel drivers on this
     * authorized device; never disconnect another userspace (usbfs) owner. */
    unsigned int detached[256];
    unsigned int detached_count = 0;
    for (unsigned int iface = 0; iface < 256; iface++) {
        struct usbdevfs_getdriver driver;
        memset(&driver, 0, sizeof(driver));
        driver.interface = iface;
        if (ioctl(file_descriptor, USBDEVFS_GETDRIVER, &driver) != 0) continue;
        if (strncmp(driver.driver, "usbfs", sizeof(driver.driver)) == 0) {
            result = EBUSY;
            goto restore_drivers;
        }
        struct usbdevfs_ioctl disconnect;
        memset(&disconnect, 0, sizeof(disconnect));
        disconnect.ifno = (int) iface;
        disconnect.ioctl_code = USBDEVFS_DISCONNECT;
        if (ioctl(file_descriptor, USBDEVFS_IOCTL, &disconnect) != 0) {
            result = errno != 0 ? errno : EIO;
            goto restore_drivers;
        }
        detached[detached_count++] = iface;
    }
    if (ioctl(file_descriptor, USBDEVFS_SETCONFIGURATION, &configuration) == 0) return 0;
    result = errno != 0 ? errno : EIO;

restore_drivers:
    for (unsigned int i = 0; i < detached_count; i++) {
        struct usbdevfs_ioctl reconnect;
        memset(&reconnect, 0, sizeof(reconnect));
        reconnect.ifno = (int) detached[i];
        reconnect.ioctl_code = USBDEVFS_CONNECT;
        (void) ioctl(file_descriptor, USBDEVFS_IOCTL, &reconnect);
    }
    return result;
}

/* Raw USBDEVFS_CONTROL, bypassing UsbDeviceConnection.controlTransfer().
 *
 * The framework path collapses every failure into -1, so a device STALL and a transfer
 * timeout look identical. That ambiguity blocked the CDC-NCM control plane: every class
 * request carrying an OUT data stage failed while every zero-length one succeeded
 * (22 failures vs 44 successes across the 2026-11-06/07 wired reports). Issuing the same
 * request through usbfs returns -errno instead, which separates EPIPE (device stalled the
 * data stage) from ETIMEDOUT (the device never answered) and EPROTO (link reset).
 *
 * Returns the transferred byte count (>= 0), or -errno on failure.
 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_controlTransfer(
        JNIEnv *env, jobject self, jint file_descriptor,
        jint request_type, jint request, jint value, jint index,
        jbyteArray data, jint length, jint timeout_millis) {
    (void) self;

    if (file_descriptor < 0 || length < 0) return -EINVAL;

    jbyte *buffer = NULL;
    if (data != NULL && length > 0) {
        if ((*env)->GetArrayLength(env, data) < length) return -EINVAL;
        buffer = (*env)->GetByteArrayElements(env, data, NULL);
        if (buffer == NULL) return -ENOMEM;
    }

    struct usbdevfs_ctrltransfer transfer;
    memset(&transfer, 0, sizeof(transfer));
    transfer.bRequestType = (unsigned char) request_type;
    transfer.bRequest = (unsigned char) request;
    transfer.wValue = (unsigned short) value;
    transfer.wIndex = (unsigned short) index;
    transfer.wLength = (unsigned short) length;
    /* usbfs takes milliseconds here, same unit as the framework's timeoutMillis. */
    transfer.timeout = (unsigned int) (timeout_millis > 0 ? timeout_millis : 1);
    transfer.data = buffer;

    int result = ioctl(file_descriptor, USBDEVFS_CONTROL, &transfer);
    int saved_errno = errno;
    /* Non-zero mode copies the IN payload back into the Java array. */
    if (buffer != NULL) {
        (*env)->ReleaseByteArrayElements(env, data, buffer, result >= 0 ? 0 : JNI_ABORT);
    }

    if (result < 0) return saved_errno != 0 ? -saved_errno : -EIO;
    return result;
}
