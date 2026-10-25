package com.ipphone.network.wifi

/** The three fields we need from a "WIFI:T:WPA;S:name;P:pass;;" QR code. */
data class WifiQr(val ssid: String, val password: String, val security: String)

/**
 * Reads/writes the standard Wi-Fi QR format understood by every phone camera.
 * Pure Kotlin (no Android classes) so it can be unit-tested on the JVM.
 */
object WifiQrCodec {

    fun build(info: WifiQr): String {
        fun esc(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
            .replace(":", "\\:").replace("\"", "\\\"")
        val type = if (info.security.equals("nopass", true)) "nopass" else info.security
        return buildString {
            append("WIFI:T:").append(type).append(";S:").append(esc(info.ssid)).append(';')
            if (type != "nopass") append("P:").append(esc(info.password)).append(';')
            append(';')
        }
    }

    fun parse(text: String): WifiQr? {
        val t = text.trim()
        if (!t.startsWith("WIFI:", ignoreCase = true)) return null
        val body = t.substring(5)

        val fields = HashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inKey = true
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> {
                    (if (inKey) key else value).append(body[i + 1]); i++
                }
                inKey && c == ':' -> inKey = false
                !inKey && c == ';' -> {
                    fields[key.toString().uppercase()] = value.toString()
                    key.clear(); value.clear(); inKey = true
                }
                c == ';' && inKey -> key.clear()
                else -> (if (inKey) key else value).append(c)
            }
            i++
        }
        if (key.isNotEmpty() && !inKey) fields[key.toString().uppercase()] = value.toString()

        val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
        return WifiQr(ssid, fields["P"].orEmpty(), fields["T"]?.takeIf { it.isNotEmpty() } ?: "WPA")
    }
}
