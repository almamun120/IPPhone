package com.ipphone.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Shrinks big camera photos before they are sent in a chat (a 6 MB photo becomes ~400 KB),
 * so chat images arrive in a second even on a weak Wi-Fi link. Originals are untouched and
 * still sent as-is through the file browser / "File" attachment type.
 */
object ImageCompressor {

    data class Result(val uri: Uri, val name: String, val size: Long, val mimeType: String)

    private const val MAX_EDGE = 1600
    private const val QUALITY = 82
    private const val SKIP_BELOW_BYTES = 350 * 1024L

    fun compressForChat(
        context: Context,
        uri: Uri,
        originalName: String,
        mimeType: String?,
        originalSize: Long
    ): Result? {
        val mime = mimeType.orEmpty().lowercase()
        if (!mime.startsWith("image/") || mime.contains("gif") || mime.contains("svg")) return null
        if (originalSize in 1 until SKIP_BELOW_BYTES) return null

        return try {
            val resolver = context.contentResolver

            // 1. Read the size only, then decode a down-sampled bitmap (keeps memory low).
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_EDGE && bounds.outHeight / (sample * 2) >= MAX_EDGE) {
                sample *= 2
            }
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) }
                ?: return null

            // PNGs with transparency would turn black as JPEG — leave those alone.
            if (mime.contains("png") && bitmap.hasAlpha()) {
                bitmap.recycle()
                return null
            }

            // 2. Honour the camera's EXIF rotation.
            val orientation = try {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
            }

            // 3. Scale so the longest edge is at most MAX_EDGE.
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest > MAX_EDGE) {
                val scale = MAX_EDGE.toFloat() / longest
                matrix.postScale(scale, scale)
            }
            if (!matrix.isIdentity) {
                val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (transformed !== bitmap) bitmap.recycle()
                bitmap = transformed
            }

            // 4. Write JPEG into the cache.
            val dir = File(context.cacheDir, "chat_compress").apply { mkdirs() }
            cleanOld(dir)
            val out = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            bitmap.recycle()

            // Only worth it when it is clearly smaller.
            if (originalSize > 0 && out.length() >= originalSize * 0.9) {
                out.delete()
                return null
            }

            val base = FileNames.splitExtension(FileNames.sanitizeFileName(originalName, "photo")).first
            Result(Uri.fromFile(out), "$base.jpg", out.length(), "image/jpeg")
        } catch (e: Throwable) {
            Timber.w(e, "Image compression failed — sending the original")
            null
        }
    }

    private fun cleanOld(dir: File) {
        val limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }
}
