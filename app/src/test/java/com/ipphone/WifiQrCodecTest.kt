package com.ipphone

import com.ipphone.network.wifi.WifiQr
import com.ipphone.network.wifi.WifiQrCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiQrCodecTest {

    @Test
    fun `round trip with plain values`() {
        val qr = WifiQr("AndroidShare_1234", "abcd1234", "WPA")
        assertEquals(qr, WifiQrCodec.parse(WifiQrCodec.build(qr)))
    }

    @Test
    fun `round trip with special characters`() {
        val qr = WifiQr("My;Wi:Fi,\\net", "p;a:s,s\"w\\d", "SAE")
        assertEquals(qr, WifiQrCodec.parse(WifiQrCodec.build(qr)))
    }

    @Test
    fun `parses what a phone camera would produce`() {
        val qr = WifiQrCodec.parse("WIFI:T:WPA;S:Home;P:secret;;")
        assertEquals(WifiQr("Home", "secret", "WPA"), qr)
    }

    @Test
    fun `open network has no password`() {
        val text = WifiQrCodec.build(WifiQr("Cafe", "", "nopass"))
        assertEquals("WIFI:T:nopass;S:Cafe;;", text)
        assertEquals("", WifiQrCodec.parse(text)?.password)
    }

    @Test
    fun `non wifi text is rejected`() {
        assertNull(WifiQrCodec.parse("https://example.com"))
        assertNull(WifiQrCodec.parse("WIFI:T:WPA;P:nopeer;;"))
    }
}
