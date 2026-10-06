package com.ipphone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun `path traversal is reduced to the last segment`() {
        assertEquals("passwd", FileNames.sanitizeFileName("../../etc/passwd"))
        assertEquals("evil.sh", FileNames.sanitizeFileName("..\\..\\evil.sh"))
    }

    @Test
    fun `reserved characters are replaced`() {
        val out = FileNames.sanitizeFileName("a:b*c?d\"e<f>g|h.txt")
        assertEquals("a_b_c_d_e_f_g_h.txt", out)
    }

    @Test
    fun `content uri style names are kept usable`() {
        assertEquals("report.pdf", FileNames.sanitizeFileName("primary:Download/report.pdf"))
    }

    @Test
    fun `empty and dot-only names fall back`() {
        assertEquals("file", FileNames.sanitizeFileName(""))
        assertEquals("file", FileNames.sanitizeFileName(".."))
        assertEquals("file", FileNames.sanitizeFileName(null))
        assertEquals("x", FileNames.sanitizeFileName("   ", "x"))
    }

    @Test
    fun `unicode names such as Bengali are preserved`() {
        assertEquals("ছবি.jpg", FileNames.sanitizeFileName("ছবি.jpg"))
    }

    @Test
    fun `long names are shortened but keep the extension`() {
        val out = FileNames.sanitizeFileName("a".repeat(300) + ".mp4")
        assertTrue(out.length <= 120)
        assertTrue(out.endsWith(".mp4"))
    }

    @Test
    fun `control and bidi characters are removed`() {
        val out = FileNames.sanitizeFileName("photo\u202Egpj.exe")
        assertFalse(out.contains('\u202E'))
    }

    @Test
    fun `split extension`() {
        assertEquals("photo.tar" to ".gz", FileNames.splitExtension("photo.tar.gz"))
        assertEquals("README" to "", FileNames.splitExtension("README"))
        assertEquals(".hidden" to "", FileNames.splitExtension(".hidden"))
    }
}
