package com.shihab.diplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FileProviderPathTestShadow::class])
class DiagnosticExportFallbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val reports get() = File(context.getExternalFilesDir(null)!!, "diagnostic-reports")
    private val privateReports get() = File(context.filesDir, "diagnostic-reports")
    private val downloads get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    @Before fun cleanReports() {
        reports.deleteRecursively()
        privateReports.deleteRecursively()
        downloads.deleteRecursively()
        downloads.mkdirs()
        registerReportProvider()
    }

    @Test fun androidNineDefaultsToOneFileInDownloads() {
        val report = "DiPlay · تقرير\nUSB: waiting\n"
        val saved = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", report)
        assertFalse(saved.savedInApp)
        val file = File(downloads, "DiPlay-test.txt")
        assertEquals(report, file.readText(Charsets.UTF_8))
        assertEquals(file.canonicalPath, File(saved.savedPath!!).canonicalPath)
        assertEquals("file", saved.uri.scheme)
        assertEquals(report, read(saved.uri))
        assertFalse(reports.exists())
    }

    @Test @Config(sdk = [29]) fun missingMediaStoreFallsBackToLegacyDownloads() {
        val provider = MissingDownloadsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val saved = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", "report")
        assertTrue(provider.insertAttempted)
        assertFalse(saved.savedInApp)
        assertEquals("report", File(downloads, "DiPlay-test.txt").readText())
        assertFalse(reports.exists())
        assertEquals("report", read(saved.uri))
    }

    @Test fun selectedWritableUsbVolumeIsTheOnlySavedLocation() {
        val usb = File(context.cacheDir, "usb-volume").apply {
            deleteRecursively()
            mkdirs()
        }
        val content = "DiPlay USB report\nAPI=19\n"
        val saved = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test-usb.txt", content, usb)
        val copy = File(usb, "DiPlay-test-usb.txt").canonicalFile
        assertTrue(copy.isFile)
        assertEquals(content, copy.readText(Charsets.UTF_8))
        assertEquals(copy, File(saved.savedPath!!).canonicalFile)
        assertFalse(File(usb, "DiPlay").exists())
        assertFalse(reports.exists())
        assertTrue(downloads.listFiles().orEmpty().isEmpty())
        assertEquals(content, read(saved.uri))
        assertTrue(saved.delete(context))
        assertFalse(copy.exists())
    }

    @Test fun unavailableSelectedUsbVolumeDoesNotClaimSuccess() {
        val blocked = File(context.cacheDir, "not-a-directory").apply { writeText("block") }
        assertThrows(IOException::class.java) {
            DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test-usb.txt", "report", blocked)
        }
        assertTrue(downloads.listFiles().orEmpty().isEmpty())
        assertFalse(reports.exists())
    }
    @Test fun anEarlierShareUriCannotReadALaterExport() {
        val first = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-first.txt", "first")
        val second = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-second.txt", "second")
        assertNotEquals(first.uri, second.uri)
        assertEquals("first", read(first.uri))
        assertEquals("second", read(second.uri))
    }

    @Test fun defaultExportsRemainInDownloads() {
        var latest: Uri? = null
        repeat(12) { latest = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test-$it.txt", "report $it").uri }
        assertEquals(12, downloads.listFiles()!!.size)
        assertFalse(reports.exists())
        assertEquals("report 11", read(latest!!))
    }

    @Test fun providerCannotExposeSessionLogsOrOtherPrivateFiles() {
        DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", "report")
        for (path in listOf("logs/diplay.log", "other.txt")) {
            val file = File(context.filesDir, path).apply { parentFile!!.mkdirs(); writeText("private") }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            }
        }
        val externalFile = File(context.getExternalFilesDir(null), "other.txt").apply { writeText("private") }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", externalFile)
        }
    }

    @Test fun unavailableExternalStorageStillSavesPrivately() {
        val blockedRoot = blockedDownloads()
        val unavailableExternal = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File? = null
        }
        val saved = DiagnosticExportStore.saveWithoutPicker(
            unavailableExternal,
            "DiPlay-test.txt",
            "report",
            defaultDownloadsRoot = blockedRoot,
        )
        assertTrue(saved.savedInApp)
        assertNull(saved.savedPath)
        assertEquals("report", read(saved.uri))
        assertEquals("report", privateReports.listFiles()!!.single().readText())
    }

    @Test fun unwritableExternalStorageStillSavesPrivately() {
        val blockedRoot = blockedDownloads()
        reports.writeText("blocks directory creation")
        val saved = DiagnosticExportStore.saveWithoutPicker(
            context,
            "DiPlay-test.txt",
            "report",
            defaultDownloadsRoot = blockedRoot,
        )
        assertTrue(saved.savedInApp)
        assertEquals("report", read(saved.uri))
        assertEquals("blocks directory creation", reports.readText())
    }

    @Test fun unavailableExternalAndPrivateStorageDoesNotReportSuccess() {
        val blockedRoot = blockedDownloads()
        reports.writeText("blocks directory creation")
        privateReports.writeText("blocks directory creation")
        assertThrows(IOException::class.java) {
            DiagnosticExportStore.saveWithoutPicker(
                context,
                "DiPlay-test.txt",
                "report",
                defaultDownloadsRoot = blockedRoot,
            )
        }
    }

    @Test fun onlyEightPrivateExportsAreRetainedWhenExternalStorageIsUnavailable() {
        val blockedRoot = blockedDownloads()
        reports.writeText("blocks directory creation")
        var latest: Uri? = null
        repeat(12) {
            latest = DiagnosticExportStore.saveWithoutPicker(
                context,
                "DiPlay-test.txt",
                "report $it",
                defaultDownloadsRoot = blockedRoot,
            ).uri
        }
        assertEquals(8, privateReports.listFiles()!!.size)
        assertEquals("report 11", read(latest!!))
    }

    private fun read(uri: Uri) = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }

    private fun blockedDownloads(): File {
        return File(context.cacheDir, "blocked-downloads").apply {
            deleteRecursively()
            parentFile!!.mkdirs()
            writeText("blocks directory creation")
        }
    }

    private fun registerReportProvider() {
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        // Robolectric gives each test a new filesDir; refresh FileProvider's static path cache.
        val provider = DiagnosticReportProvider().also { it.attachInfo(context, info) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    private class MissingDownloadsProvider : ContentProvider() {
        var insertAttempted = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? { insertAttempted = true; return null }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
