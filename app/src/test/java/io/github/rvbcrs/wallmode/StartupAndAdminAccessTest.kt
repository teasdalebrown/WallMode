package io.github.rvbcrs.wallmode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupAndAdminAccessTest {
    @Test
    fun startupDiscoveryRequiresOptInAndNeverReplacesAChosenServer() {
        assertTrue(KioskPreferences.shouldAutoDiscoverHomeAssistant(true, "http://homeassistant.local:8123"))
        assertTrue(KioskPreferences.shouldAutoDiscoverHomeAssistant(true, " HTTP://HOMEASSISTANT.LOCAL:8123/ "))
        assertFalse(KioskPreferences.shouldAutoDiscoverHomeAssistant(false, KioskPreferences.DEFAULT_HOME_ASSISTANT_URL))
        listOf("http://192.168.0.248:3124/?d=main", "http://192.168.0.248:8123", "https://ha.example.com",
            "http://homeassistant.local:8123/custom").forEach {
            assertFalse("A selected dashboard must be preserved: $it", KioskPreferences.shouldAutoDiscoverHomeAssistant(true, it))
        }
    }

    @Test
    fun localAdminUnlockIsCredentialBoundAndExpiresAtTheConfiguredMonotonicDeadline() {
        val session = AdminUnlockSession("password-hash", 10_000)
        assertTrue(session.isValid("password-hash", 10_000, 1))
        assertTrue(session.isValid("password-hash", 69_999, 1))
        assertFalse(session.isValid("password-hash", 70_000, 1))
        assertTrue(session.isValid("password-hash", 70_000, 2))
        assertFalse(session.isValid("replacement-hash", 10_001, 1))
        assertFalse(session.isValid("", 10_001, 1))
        assertFalse(session.isValid("password-hash", 9_999, 1))
        assertFalse(session.isValid("password-hash", 10_000, 0))
        assertFalse(AdminUnlockSession("", 10_000).isValid("", 10_001, 1))
        assertFalse(AdminUnlockSession("password-hash", -1).isValid("password-hash", 0, 1))
    }
}
