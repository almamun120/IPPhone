package com.ipphone.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import timber.log.Timber
import java.io.ByteArrayOutputStream

object ThumbnailUtils {

    fun generateThumbnail(context: Context, uri: Uri, mimeType: String?): String? {
        return try {
            val bitmap = when {
                mimeType?.startsWith("image") == true -> {
                    createImageThumbnail(context, uri)
                }
                mimeType?.startsWith("video") == true -> {
                    createVideoThumbnail(context, uri)
                }
                else -> null
            }

            bitmap?.let {
                val outputStream = ByteArrayOutputStream()
                it.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                val byteArray = outputStream.toByteArray()
                Base64.encodeToString(byteArray, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate thumbnail for $uri")
            null
        }
    }

    private fun createImageThumbnail(context: Context, uri: Uri): Bitmap? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        val targetWidth = 200
        val targetHeight = 200
        var inSampleSize = 1

        if (options.outHeight > targetHeight || options.outWidth > targetWidth) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while (halfHeight / inSampleSize >= targetHeight && halfWidth / inSampleSize >= targetWidth) {
                inSampleSize *= 2
            }
        }

        val secondStream = context.contentResolver.openInputStream(uri) ?: return null
        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        val bitmap = BitmapFactory.decodeStream(secondStream, null, options)
        secondStream.close()
        return bitmap
    }

    private fun createVideoThumbnail(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract video frame")
            null
        } finally {
            retriever.release()
        }
    }
}

