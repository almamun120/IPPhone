package com.ipphone.presentation.screens.chat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.ipphone.utils.FileUtils
import timber.log.Timber
import java.io.File

data class AttachmentFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String?,
    val durationMs: Long? = null   // for audio/video
)

object ChatAttachmentHelper {

    fun resolveFiles(context: Context, uris: List<Uri>): List<AttachmentFile> {
        return uris.mapNotNull { uri -> resolve(context, uri) }
    }

    private fun resolve(context: Context, uri: Uri): AttachmentFile? {
        return try {
            val name     = FileUtils.getFileName(context, uri) ?: "file"
            val size     = FileUtils.getFileSize(context, uri)
            val mimeType = FileUtils.getMimeType(context, uri)
            val duration = if (isAudioOrVideo(mimeType)) getDuration(context, uri) else null
            AttachmentFile(uri, name, size, mimeType, duration)
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve URI: $uri")
            null
        }
    }

    private fun isAudioOrVideo(mimeType: String?): Boolean {
        return mimeType?.startsWith("audio") == true ||
                mimeType?.startsWith("video") == true

    }

    private fun getDuration(context: Context, uri: Uri): Long? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val dur = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            retriever.release()
            dur
        } catch (e: Exception) {
            null
        }
    }

    fun categoryMime(category: AttachmentCategory): Array<String> = when (category) {
        AttachmentCategory.IMAGE    -> arrayOf("image/*")
        AttachmentCategory.VIDEO    -> arrayOf("video/*")
        AttachmentCategory.AUDIO    -> arrayOf("audio/*")
        AttachmentCategory.DOCUMENT -> arrayOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/csv"
        )
        AttachmentCategory.FILE     -> arrayOf("*/*")
    }
}

enum class AttachmentCategory {
    IMAGE, VIDEO, AUDIO, DOCUMENT, FILE
}
