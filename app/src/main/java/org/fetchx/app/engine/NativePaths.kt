package org.fetchx.app.engine

import android.content.Context
import java.io.File

/**
 * Locates the native pieces shipped by the youtubedl-android modules (aria2c, its shared libraries and a CA bundle).
 * The exact unpack layout differs between library versions, so this scans instead of hard-coding paths.
 */
object NativePaths {
    private var scanned: List<File>? = null

    @Synchronized
    private fun files(context: Context): List<File> {
        scanned?.let { return it }
        val root = File(context.noBackupFilesDir, "youtubedl-android")
        val list = if (root.exists()) root.walkTopDown().maxDepth(9).filter { it.isFile }.toList() else emptyList()
        scanned = list
        return list
    }

    @Synchronized
    fun invalidate() {
        scanned = null
    }

    fun aria2Binary(context: Context): File? {
        val native = File(context.applicationInfo.nativeLibraryDir, "libaria2c.so")
        if (native.exists()) return native
        return files(context).firstOrNull { it.name == "aria2c" || it.name == "libaria2c.so" }
    }

    fun libraryDirs(context: Context): String {
        val dirs = linkedSetOf(context.applicationInfo.nativeLibraryDir)
        files(context).filter { it.name.contains(".so") }.mapNotNullTo(dirs) { it.parentFile?.absolutePath }
        return dirs.joinToString(":")
    }

    fun caBundle(context: Context): File? =
        files(context).firstOrNull { it.name == "cacert.pem" || it.name == "cert.pem" || it.name == "ca-certificates.crt" }
}
