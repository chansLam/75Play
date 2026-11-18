package com.shihab.diplay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PrivilegedNativeLibrariesTest {
    @Test
    fun libraryFileNameAcceptsOnlyFlatArmeabiV7aLibraries() {
        assertEquals(
            "libusb_host_compat.so",
            PrivilegedNativeLibraries.libraryFileName("lib/armeabi-v7a/libusb_host_compat.so"),
        )
        assertNull(PrivilegedNativeLibraries.libraryFileName("lib/arm64-v8a/libusb_host_compat.so"))
        assertNull(PrivilegedNativeLibraries.libraryFileName("lib/armeabi-v7a/nested/libnope.so"))
        assertNull(PrivilegedNativeLibraries.libraryFileName("assets/readme.txt"))
    }

    @Test
    fun extractCopiesArmeabiV7aLibrariesAndLeavesMatchingFiles() {
        val root = java.nio.file.Files.createTempDirectory("native-libs").toFile()
        val apk = File(root, "app.apk")
        val dest = File(root, "out")
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("lib/armeabi-v7a/libusb_host_compat.so", byteArrayOf(1, 2, 3))
            put("lib/armeabi-v7a/liblwip_ncm_relay.so", byteArrayOf(4, 5))
            put("lib/arm64-v8a/libusb_host_compat.so", byteArrayOf(9))
            put("lib/armeabi-v7a/nested/libnope.so", byteArrayOf(7))
        }

        assertEquals(2, PrivilegedNativeLibraries.extractFromApk(apk, dest))
        assertEquals(3, File(dest, "libusb_host_compat.so").length())
        assertEquals(2, File(dest, "liblwip_ncm_relay.so").length())
        assertFalse(File(dest, "libnope.so").exists())

        val kept = File(dest, "libusb_host_compat.so")
        val modified = kept.lastModified()
        assertEquals(2, PrivilegedNativeLibraries.extractFromApk(apk, dest))
        assertEquals(modified, kept.lastModified())
        assertTrue(root.deleteRecursively())
    }
}
