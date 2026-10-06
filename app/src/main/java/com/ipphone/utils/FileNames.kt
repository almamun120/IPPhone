package com.ipphone.utils

/**
 * Pure (Android-free) helpers for turning untrusted, peer-supplied names into
 * safe local file names. Kept separate from FileUtils so it can be unit-tested.
 */
object FileNames {

    private const val MAX_NAME_LENGTH = 120
    private const val MAX_EXTENSION_LENGTH = 16

    // Path separators, Windows-reserved characters, ASCII control characters and
    // the bidi-override characters that are used to spoof file extensions.
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F\\u202A-\\u202E\\u2066-\\u2069]")

    /**
     * Returns a name that is always a single path segment, never empty, never
     * "." or "..", free of reserved characters and no longer than ~120 chars
     * (the extension is preserved when the name has to be shortened).
     */
    fun sanitizeFileName(raw: String?, fallback: String = "file"): String {
        var name = raw.orEmpty().trim()

        // Keep only the last path segment (handles both '/' and '\').
        name = name.substringAfterLast('/').substringAfterLast('\\')
        name = FORBIDDEN.replace(name, "_")
        name = name.trim().trimEnd('.', ' ')

        if (name.isEmpty() || name.all { it == '.' || it == '_' || it == ' ' }) {
            name = fallback
        }

        if (name.length > MAX_NAME_LENGTH) {
            val dot = name.lastIndexOf('.')
            val ext = if (dot > 0 && name.length - dot <= MAX_EXTENSION_LENGTH) name.substring(dot) else ""
            val base = name.substring(0, name.length - ext.length)
            name = base.take(MAX_NAME_LENGTH - ext.length) + ext
        }
        return name
    }

    /** True when [name] already ends in something that looks like an extension. */
    fun hasExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        return dot > 0 && dot < name.length - 1 && name.length - dot <= MAX_EXTENSION_LENGTH
    }

    /** Splits "photo.tar.gz" into ("photo.tar", ".gz"); no extension -> (name, ""). */
    fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) {
            name.substring(0, dot) to name.substring(dot)
        } else {
            name to ""
        }
    }
}
