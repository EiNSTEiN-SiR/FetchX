package org.fetchx.app.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** Moves finished files out of the app's private work directory to the user's chosen destination. */
object Exporter {
    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Returns the content uri of the exported file as a string. */
    fun export(context: Context, file: File, treeUri: String?): String {
        val mime = mimeOf(file.name)
        return if (treeUri.isNullOrBlank()) exportToMediaStore(context, file, mime) else exportToTree(context, file, mime, treeUri)
    }

    private fun exportToMediaStore(context: Context, file: File, mime: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FetchX")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create file in Downloads")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: error("Could not open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri.toString()
    }

    private fun exportToTree(context: Context, file: File, mime: String, treeUri: String): String {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: error("Destination folder is not accessible")
        if (!tree.canWrite()) error("No write permission for the destination folder; pick it again in Settings")
        var name = file.name
        var n = 1
        while (tree.findFile(name) != null) {
            val base = file.nameWithoutExtension
            val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
            name = "$base ($n)$ext"
            n++
        }
        val target = tree.createFile(mime, name) ?: error("Could not create $name in the destination folder")
        context.contentResolver.openOutputStream(target.uri)?.use { out ->
            file.inputStream().use { it.copyTo(out, 256 * 1024) }
        } ?: error("Could not open output stream")
        return target.uri.toString()
    }
}
