package com.shihab.diplay

import com.shihab.diplay.util.AppLog

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

internal class DiagnosticExportCleanupException(message: String, cause: Throwable) : IOException(message, cause)

/** Saves an app-owned report without depending on an OEM's document-picker activity. */
internal object DiagnosticExportStore {
    data class SavedReport(
        val uri: Uri,
        val savedInApp: Boolean = false,
        val savedPath: String? = null,
        private val storedFile: File? = null,
    ) {
        /** Removes the server-side copy after a complete one-time QR download. */
        fun delete(context: Context): Boolean = if (storedFile != null) {
            !storedFile.exists() || storedFile.delete()
        } else {
            runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        }

        fun deleteAfterDownload(context: Context): Boolean {
            repeat(3) { attempt ->
                if (delete(context)) return true
                if (attempt < 2) {
                    try {
                        Thread.sleep(100L * (attempt + 1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            }
            return false
        }
    }

    /** Android 9 and OEMs without working Downloads storage can still export privately. */
    fun saveWithoutPicker(context: Context, fileName: String, report: String, preferredRoot: File? = null): SavedReport {
        if (preferredRoot != null) {
            return saveSelectedDirectory(preferredRoot, fileName, report)
        }

        val publicDownload = runCatching {
            SavedReport(Api29DiagnosticDownloads.save(context.contentResolver, fileName, report))
        }
        publicDownload.getOrNull()?.let { return it }
        val publicDownloadFailure = publicDownload.exceptionOrNull()
        if (publicDownloadFailure is DiagnosticExportCleanupException) throw publicDownloadFailure
        publicDownloadFailure?.takeUnless {
            it is ClassNotFoundException || it is NoSuchFieldException
        }?.let {
            AppLog.w("DiagnosticExportStore", "save to MediaStore Downloads failed; trying legacy Downloads", it)
        }

        val legacyDownload = runCatching { saveLegacyDownloads(fileName, report) }
        legacyDownload.getOrNull()?.let { return it }
        val legacyDownloadFailure = legacyDownload.exceptionOrNull()
        if (legacyDownloadFailure is DiagnosticExportCleanupException) throw legacyDownloadFailure
        legacyDownloadFailure?.let {
            AppLog.w("DiagnosticExportStore", "save to Downloads failed; falling back privately", it)
        }
        return try {
            // Use Android's package-specific directory, including debug application IDs.
            // No storage permission or document-picker activity is needed.
            val externalFiles = context.getExternalFilesDir(null)
            if (externalFiles != null) {
                saveInDirectory(context, File(externalFiles, "diagnostic-reports"), fileName, report)
            } else {
                saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
            }
        } catch (error: Exception) {
            if (error is DiagnosticExportCleanupException) throw error
            // A missing, read-only or full external volume must not prevent export.
            saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
        }
    }

    /** Exposes writable USB-like volumes to the in-app KitKat destination picker. */
    fun availableUsbVolumes(context: Context): List<File> {
        val candidates = linkedSetOf<File>()
        runCatching {
            context.getExternalFilesDirs(null)?.drop(1)?.filterNotNull()?.forEach { candidates.add(it) }
        }
        val usbName = Regex("(?i).*(usb|udisk|usbotg|otg|removable|external_usb).*")
        for (base in listOf("/storage", "/mnt", "/mnt/media_rw")) {
            for (child in File(base).listFiles().orEmpty()) {
                if (!child.isDirectory) continue
                if (usbName.matches(child.name)) candidates.add(child)
                for (sub in child.listFiles().orEmpty()) {
                    if (sub.isDirectory && (usbName.matches(child.name) || usbName.matches(sub.name))) {
                        candidates.add(sub)
                    }
                }
            }
        }
        return candidates.filter { it.isDirectory && it.canWrite() }.distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
    }
    /**
     * Head units often expose a single USB volume through several mount points at once
     * (`/storage/xxxx`, `/mnt/usbhostN`, `/mnt/media_rw/xxxx`, app external dirs). Dedupe by
     * canonical path so the same volume receives exactly one report copy instead of one per
     * mount point. See issue #108.
     */
    internal fun dedupeExportRoots(candidates: List<File>): List<File> {
        val seen = linkedSetOf<String>()
        val roots = mutableListOf<File>()
        for (candidate in candidates) {
            val key = runCatching { candidate.canonicalPath }.getOrDefault(candidate.absolutePath)
            if (seen.add(key)) roots.add(File(key))
        }
        return roots
    }

    private fun saveLegacyDownloads(fileName: String, report: String): SavedReport {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.isDirectory && !downloads.mkdirs()) {
            throw IOException("Downloads directory is unavailable")
        }
        return saveSelectedDirectory(downloads, fileName, report)
    }

    private fun saveSelectedDirectory(directory: File, fileName: String, report: String): SavedReport {
        val root = directory.canonicalFile
        if (!root.isDirectory || !root.canWrite()) throw IOException("Report destination is unavailable")
        val file = File(root, fileName).canonicalFile
        if (file.parentFile != root || file.exists()) throw IOException("Report destination is unavailable")
        try {
            file.writeText(report, Charsets.UTF_8)
            return SavedReport(Uri.fromFile(file), savedPath = file.absolutePath, storedFile = file)
        } catch (error: Exception) {
            if (file.exists() && !file.delete()) {
                throw DiagnosticExportCleanupException("Could not clean up the incomplete report", error)
            }
            throw error
        }
    }

    private fun saveInDirectory(
        context: Context,
        directory: File,
        fileName: String,
        report: String,
        savedInApp: Boolean = false,
    ): SavedReport {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Report storage is unavailable")
        // Each export has a new URI: an earlier share grant cannot read a later report.
        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        try {
            file.writeText(report, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            // Retain only the newest eight reports; never prune the export being returned.
            directory.listFiles()?.filter { it != file && it.isFile }
                ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            return SavedReport(
                uri,
                savedInApp = savedInApp,
                savedPath = if (savedInApp) null else file.absolutePath,
                storedFile = file,
            )
        } catch (error: Exception) {
            if (file.exists() && !file.delete()) {
                throw DiagnosticExportCleanupException("Could not clean up the incomplete report", error)
            }
            throw error
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, report: String) {
        val stream = resolver.openOutputStream(uri)
            ?: throw IOException("Report destination is unavailable")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
    }
}
