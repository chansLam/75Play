package com.shihab.diplay

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import java.io.IOException

/** Optional scoped Downloads provider accessed without linking API 20+ platform symbols. */
internal object Api29DiagnosticDownloads {
    @JvmStatic
    fun save(resolver: ContentResolver, fileName: String, report: String): Uri {
        val downloads = Class.forName("android.provider.MediaStore\$Downloads")
        val externalUri = downloads.getField("EXTERNAL_CONTENT_URI").get(null) as Uri
        val values = ContentValues().apply {
            put("_display_name", fileName)
            put("mime_type", "text/plain")
            put("relative_path", "Download/DiPlay")
            put("is_pending", 1)
        }
        val uri = resolver.insert(externalUri, values)
            ?: throw IOException("Downloads could not create the report")
        try {
            DiagnosticExportStore.write(resolver, uri, report)
            val published = resolver.update(uri, ContentValues().apply {
                put("is_pending", 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the report")
            return uri
        } catch (error: Exception) {
            val removed = runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            if (!removed) {
                throw DiagnosticExportCleanupException(
                    "Downloads could not clean up the incomplete report",
                    error,
                )
            }
            throw error
        }
    }
}
