LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := airplay_chacha
LOCAL_SRC_FILES := chacha_jni.c chacha/chacha20.c chacha/poly1305.c chacha/aead.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_CFLAGS := -O2 -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := speex_echo
LOCAL_SRC_FILES := speex_echo_jni.c \
    speexdsp/mdf.c speexdsp/preprocess.c speexdsp/fftwrap.c \
    speexdsp/kiss_fft.c speexdsp/kiss_fftr.c speexdsp/filterbank.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/speexdsp $(LOCAL_PATH)/speexdsp/include
LOCAL_CFLAGS := -O2 -DFIXED_POINT -DUSE_KISS_FFT -DEXPORT= -Wno-unused-parameter
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)

# Software Opus microphone encoder. The pinned upstream source is restored by
# scripts/prepare-libopus.py (into shared/src/main/jni/libopus) before ndk-build.
# Keep libopus's published source lists; do not depend on platform MediaCodec.
# Fail the build when the pinned libopus source is missing rather than silently
# disabling the fallback.
ifeq ($(wildcard $(LOCAL_PATH)/libopus/opus_sources.mk),)
$(error libopus sources missing: run scripts/prepare-libopus.py)
endif
include $(LOCAL_PATH)/libopus/opus_sources.mk
include $(LOCAL_PATH)/libopus/celt_sources.mk
include $(LOCAL_PATH)/libopus/silk_sources.mk

include $(CLEAR_VARS)
LOCAL_MODULE := opus_microphone
LOCAL_SRC_FILES := opus_encoder_jni.c opus_decoder_jni.c \
    $(addprefix libopus/,$(OPUS_SOURCES) \
    $(CELT_SOURCES) $(SILK_SOURCES) $(SILK_SOURCES_FIXED))
LOCAL_C_INCLUDES := $(LOCAL_PATH)/libopus/include \
    $(LOCAL_PATH)/libopus/celt $(LOCAL_PATH)/libopus/silk \
    $(LOCAL_PATH)/libopus/silk/fixed
LOCAL_CFLAGS := -O2 -DOPUS_BUILD -DFIXED_POINT=1 -DDISABLE_FLOAT_API -DUSE_ALLOCA
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)

# Userspace lwIP TCP/IP stack for NCM Ethernet relay (wired CarPlay, no VpnService/TUN).
# The pinned upstream lwIP source is restored by scripts/prepare-lwip.py
# (into shared/src/main/jni/lwip) before ndk-build, mirroring libopus handling.
ifeq ($(wildcard $(LOCAL_PATH)/lwip/src/include/lwip/opt.h),)
$(error lwip sources missing: run scripts/prepare-lwip.py)
endif

include $(CLEAR_VARS)
LOCAL_MODULE := lwip_ncm_relay
LOCAL_SRC_FILES := lwip_ncm_relay.c \
    lwip/src/core/init.c \
    lwip/src/core/def.c \
    lwip/src/core/inet_chksum.c \
    lwip/src/core/ip.c \
    lwip/src/core/mem.c \
    lwip/src/core/memp.c \
    lwip/src/core/netif.c \
    lwip/src/core/pbuf.c \
    lwip/src/core/raw.c \
    lwip/src/core/sys.c \
    lwip/src/core/tcp.c \
    lwip/src/core/tcp_in.c \
    lwip/src/core/tcp_out.c \
    lwip/src/core/timeouts.c \
    lwip/src/core/udp.c \
    lwip/src/core/ipv4/etharp.c \
    lwip/src/core/ipv4/icmp.c \
    lwip/src/core/ipv4/ip4.c \
    lwip/src/core/ipv4/ip4_addr.c \
    lwip/src/core/ipv4/ip4_frag.c \
    lwip/src/core/ipv6/ethip6.c \
    lwip/src/core/ipv6/icmp6.c \
    lwip/src/core/ipv6/inet6.c \
    lwip/src/core/ipv6/ip6.c \
    lwip/src/core/ipv6/ip6_addr.c \
    lwip/src/core/ipv6/mld6.c \
    lwip/src/core/ipv6/nd6.c \
    lwip/src/netif/ethernet.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/lwip_port \
    $(LOCAL_PATH)/lwip/src/include
LOCAL_CFLAGS := -O2 -Wno-unused-parameter -Wno-sign-compare
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

# usbfs fallbacks for Android 9 and older head units (API 19: no framework
# setConfiguration, and a vendor driver can hold the NCM interface). Loaded by
# LegacyUsbHostNative / LegacyNcmNative; without these two .so files every wired
# CarPlay connection fails with setConfiguration errno=null, activeConfig stays 1.
include $(CLEAR_VARS)
LOCAL_MODULE := usb_host_compat
LOCAL_SRC_FILES := usb_host_compat.c
LOCAL_CFLAGS := -Wall -Wextra
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := legacy_ncm_claim
LOCAL_SRC_FILES := legacy_ncm_claim.c
LOCAL_CFLAGS := -Wall -Wextra
include $(BUILD_SHARED_LIBRARY)
