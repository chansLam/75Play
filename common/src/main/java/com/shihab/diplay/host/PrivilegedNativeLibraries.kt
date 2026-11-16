package com.shihab.diplay.host

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * KitKat does not unpack JNI for a privileged system app, and on boot it deletes whatever was
 * left in the app lib directory. Wired CarPlay then dies on the first `loadLibrary` even though
 * USB permission was granted silently. The libraries stay inside the apk under
 * `lib/armeabi-v7a`, so copy them back before any native class is initialized.
 */
object PrivilegedNativeLibraries {
    private const val TAG = "DiPlay-NativeLibs"
    private const val ABI_PREFIX = "lib/armeabi-v7a/"

    /** File name of an armeabi-v7a library entry, or null when [entryName] is not one. */
    fun libraryFileName(entryName: String): String? {
        if (!entryName.startsWith(ABI_PREFIX) || !entryName.endsWith(".so")) return null
        val name = entryName.substring(ABI_PREFIX.length)
        if (name.isEmpty() || name.contains('/')) return null
        return name
    }

    /**
     * Copies armeabi-v7a libraries from [apk] into [destDir]. Existing files of the same length
     * are left in place. Returns how many libraries are present afterwards.
     */
    fun extractFromApk(apk: File, destDir: File): Int {
        if (!apk.isFile) return 0
        if (!destDir.isDirectory && !destDir.mkdirs()) return 0
        if (!destDir.canWrite()) return 0
        var count = 0
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = libraryFileName(entry.name) ?: continue
                val target = File(destDir, name)
                if (target.isFile && target.length() == entry.size) {
                    count++
                    continue
                }
                val tmp = File(destDir, "$name.tmp")
                try {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    }
                    if (target.exists() && !target.delete()) {
                        tmp.delete()
                        continue
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        continue
                    }
                    target.setReadable(true, false)
                    count++
                } catch (error: Throwable) {
                    tmp.delete()
                    Log.w(TAG, "failed to extract $name: ${error.message}")
                }
            }
        }
        return count
    }

    /**
     * Makes sure this process can `loadLibrary` the apk's JNI. Writes into the app lib directory
     * when that directory is writable; otherwise extracts beside the app data and adds that
     * directory to the class loader search path.
     */
    fun ensure(context: Context) {
        val apkPath = context.applicationInfo.sourceDir ?: return
        val preferred = context.applicationInfo.nativeLibraryDir?.let { File(it) }
        val writablePreferred = preferred != null &&
            !preferred.path.startsWith("/system") &&
            (preferred.isDirectory || preferred.mkdirs()) &&
            preferred.canWrite()
        val dest = if (writablePreferred) preferred!! else context.getDir("nativelib", Context.MODE_PRIVATE)
        val count = try {
            extractFromApk(File(apkPath), dest)
        } catch (error: Throwable) {
            Log.w(TAG, "native library restore failed: ${error.message}")
            return
        }
        if (count == 0) {
            Log.w(TAG, "no armeabi-v7a libraries in $apkPath")
            return
        }
        if (!writablePreferred) {
            try {
                prependSearchPath(context.classLoader, dest)
            } catch (error: Throwable) {
                Log.w(TAG, "could not add native search path ${dest.path}: ${error.message}")
            }
        }
    }

    private fun prependSearchPath(classLoader: ClassLoader, dir: File) {
        val base = Class.forName("dalvik.system.BaseDexClassLoader")
        val pathListField = base.getDeclaredField("pathList")
        pathListField.isAccessible = true
        val pathList = pathListField.get(classLoader) ?: return
        val dirsField = pathList.javaClass.getDeclaredField("nativeLibraryDirectories")
        dirsField.isAccessible = true
        val current = (dirsField.get(pathList) as? Array<*>)?.filterIsInstance<File>().orEmpty()
        if (current.any { it.path == dir.path }) return
        dirsField.set(pathList, arrayOf(dir, *current.toTypedArray()))
    }
}
