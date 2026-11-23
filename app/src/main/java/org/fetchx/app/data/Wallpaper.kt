package org.fetchx.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File

/** Stores the custom background image. */
object Wallpaper {
    fun file(context: Context): File = File(context.filesDir, "wallpaper.jpg")

    /**
     * Imports the picked image. A persistable read grant is requested when the picker allows it, and the image is also
     * copied (downscaled, orientation applied) into app storage, because photo-picker grants do not survive a reboot.
     */
    fun import(context: Context, uri: Uri): Boolean = runCatching {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Not offered for photo-picker URIs; the copy below makes it unnecessary.
        }
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, 1920f / maxOf(w, h))
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }
        val tmp = File(context.filesDir, "wallpaper.tmp")
        tmp.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        bitmap.recycle()
        val dst = file(context)
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }.getOrDefault(false)

    fun remove(context: Context) {
        file(context).delete()
    }
}
