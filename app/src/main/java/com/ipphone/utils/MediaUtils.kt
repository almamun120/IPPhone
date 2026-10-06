package com.ipphone.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream

object MediaUtils {

    /**
     * Opens a file using an external viewer.
     */
    fun openFile(
        context: Context,
        path: String,
        mimeType: String
    ) {
        if (path.isBlank()) {
            Toast.makeText(context, "File path is empty", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri: Uri = when {
                path.startsWith("content://") -> Uri.parse(path)
                path.startsWith("file://") -> {
                    val file = File(Uri.parse(path).path ?: "")
                    if (!file.exists()) {
                        Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                        return
                    }
                    safeFileProviderUri(context, file) ?: return
                }
                else -> {
                    val file = File(path)
                    if (!file.exists()) {
                        Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                        return
                    }
                    safeFileProviderUri(context, file) ?: return
                }
            }
            val effectiveMime = mimeType.ifBlank { "*/*" }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, effectiveMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                val chooser = Intent.createChooser(intent, "Open with").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    Toast.makeText(context, "No app found to open this file type", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun safeFileProviderUri(context: Context, file: File): Uri? {
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(
                context,
                "Cannot open file: location not accessible to other apps",
                Toast.LENGTH_LONG
            ).show()
            null
        }
    }

    /**
     * Saves a media file to public Downloads/WifiCalling via MediaStore.
     */
    fun saveMediaToPublicDownloads(
        context: Context,
        path: String,
        displayName: String,
        mimeType: String,
        onDone: (success: Boolean) -> Unit = {}
    ) {
        if (path.isBlank()) {
            Toast.makeText(context, "No file to save", Toast.LENGTH_SHORT).show()
            onDone(false)
            return
        }
        MainScope().launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val effectiveMime = when {
                        mimeType.startsWith("image") -> "image/jpeg"
                        mimeType.startsWith("video") -> "video/mp4"
                        mimeType.startsWith("audio") -> "audio/mp4"
                        else -> mimeType.ifBlank { "application/octet-stream" }
                    }
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, effectiveMime)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(
                                MediaStore.MediaColumns.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS + "/WifiCalling"
                            )
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    else
                        MediaStore.Files.getContentUri("external")

                    val destUri = context.contentResolver.insert(collection, contentValues)
                        ?: return@withContext false

                    val sourceStream: InputStream? = when {
                        path.startsWith("content://") ->
                            context.contentResolver.openInputStream(Uri.parse(path))
                        path.startsWith("file://") ->
                            File(Uri.parse(path).path ?: "").inputStream()
                        else -> File(path).inputStream()
                    }
                    if (sourceStream == null) {
                        context.contentResolver.delete(destUri, null, null)
                        return@withContext false
                    }
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        sourceStream.use { it.copyTo(out) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val update = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        context.contentResolver.update(destUri, update, null, null)
                    }
                    true
                } catch (e: Exception) {
                    Timber.e(e, "saveMediaToPublicDownloads failed")
                    false
                }
            }
            if (success) {
                Toast.makeText(context, "Saved to Downloads/WifiCalling", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
            }
            onDone(success)
        }
    }
}

