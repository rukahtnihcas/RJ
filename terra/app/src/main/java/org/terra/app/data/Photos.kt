package org.terra.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Photos live in app-private storage; nothing is uploaded anywhere. */
class PhotoStore(private val context: Context) {
    val dir: File get() = File(context.filesDir, "photos").apply { mkdirs() }

    fun newFile(prefix: String): File = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun importFrom(uri: Uri, prefix: String): File? = runCatching {
        val out = newFile(prefix)
        context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }.getOrNull()

    /** Shrinks a fresh capture to a sane size and bakes in its EXIF rotation. Returns false if unreadable. */
    fun normalize(file: File, maxSide: Int = 1600): Boolean = runCatching {
        if (!file.exists() || file.length() == 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val rotation = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = (maxSide.toFloat() / maxOf(bmp.width, bmp.height)).coerceAtMost(1f)
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        true
    }.getOrDefault(false)
}
