package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserEngineTest {
    @Test
    fun removedGeckoPreferenceFallsBackToWebView() {
        assertEquals(BrowserEngine.WEBVIEW, BrowserEngine.fromStoredValue("GECKO"))
    }

    @Test
    fun dashboardUrlsRequireAValidHttpHostAndPort() {
        assertTrue(KioskPreferences.isHttpOrHttpsUrl("http://192.168.0.248:3124/?d=main"))
        assertTrue(KioskPreferences.isHttpOrHttpsUrl("http://homeassistant.local:8123"))
        assertTrue(KioskPreferences.isHttpOrHttpsUrl("https://example.com/path%20name#view"))
        assertFalse(KioskPreferences.isHttpOrHttpsUrl("javascript:alert(1)"))
        assertFalse(KioskPreferences.isHttpOrHttpsUrl("http://javascript:alert(1)"))
        assertFalse(KioskPreferences.isHttpOrHttpsUrl("http://example.com:invalid"))
        assertFalse(KioskPreferences.isHttpOrHttpsUrl("http://example.com:65536"))
        assertFalse(KioskPreferences.isHttpOrHttpsUrl("http:///missing-host"))
    }
}
