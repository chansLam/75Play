# NDK platform must be <= the device API (19): ART refuses to load a .so built for
# android-28 on a KitKat head unit. Match our minSdk so the native libs load on the CS75.
APP_PLATFORM := android-19
# Car package stays armeabi-v7a. Gradle can pass APP_ABI for an emulator build.
ifndef APP_ABI
APP_ABI := armeabi-v7a
endif
APP_STL := none
