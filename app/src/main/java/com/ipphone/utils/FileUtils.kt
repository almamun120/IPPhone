package com.ipphone.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

object FileUtils {

    fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null

        when (uri.scheme) {
            "content" -> {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = cursor.getString(nameIndex)
                        }
                    }
                }
            }
            "file" -> {
                name = uri.lastPathSegment
            }
        }

        return name
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size = 0L

        when (uri.scheme) {
            "content" -> {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            }
            "file" -> {
                uri.path?.let { path ->
                    size = File(path).length()
                }
            }
        }

        return size
    }

    fun getMimeType(context: Context, uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> context.contentResolver.getType(uri)
            "file" -> {
                val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            }
            else -> null
        }
    }

    fun calculateMD5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().toHexString()
    }

    fun calculateMD5(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data).toHexString()
    }

    /**
     * Builds a safe local file name for a file that a peer wants to send us.
     * - strips path separators / reserved characters (path traversal, odd content-URI names
     *   such as "primary:Download/a.pdf")
     * - appends an extension derived from the MIME type when the name has none
     */
    fun safeReceivedName(rawName: String?, mimeType: String?, fallback: String): String {
        var name = FileNames.sanitizeFileName(rawName, FileNames.sanitizeFileName(fallback, "file"))
        if (!FileNames.hasExtension(name) && !mimeType.isNullOrBlank()) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (!ext.isNullOrBlank()) name = "$name.$ext"
        }
        return name
    }

    /**
     * Creates a unique file inside [directory] without ever leaving it.
     * The name is sanitised here as well, so every caller is protected.
     */
    fun createUniqueFile(directory: File, fileName: String): File {
        val safeName = FileNames.sanitizeFileName(fileName)
        val (base, extension) = FileNames.splitExtension(safeName)

        var file = File(directory, safeName)
        var counter = 1
        while (file.exists()) {
            file = File(directory, "${base}_$counter$extension")
            counter++
        }

        // Defence in depth: the result must be a direct child of [directory].
        val parent = file.canonicalFile.parentFile
        if (parent == null || parent != directory.canonicalFile) {
            return File(directory, "file_${System.currentTimeMillis()}")
        }
        return file
    }

    /**
     * For internally generated ids (chat attachments): the sanitised id is the file name,
     * and an existing file is reused so a resumed stream keeps writing to the same file.
     */
    fun createUniqueFileNamed(directory: File, id: String): File =
        File(directory, FileNames.sanitizeFileName(id, "file"))

    fun getDownloadsDirectory(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "IPPhone")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getInternalSentDirectory(context: Context): File {
        val dir = File(context.filesDir, "sent_media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getInternalReceivedDirectory(context: Context): File {
        val dir = File(context.filesDir, "received_media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun copyUriToInternal(context: Context, uri: Uri, targetFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursively(child)
            }
        }
        return file.delete()
    }

    fun copyFile(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }

    fun getAllFilesInDirectory(directory: File): List<File> {
        val files = mutableListOf<File>()

        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                files.addAll(getAllFilesInDirectory(file))
            } else {
                files.add(file)
            }
        }

        return files
    }
}

