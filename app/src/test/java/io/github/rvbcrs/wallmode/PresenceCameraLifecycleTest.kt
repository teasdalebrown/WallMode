package io.github.rvbcrs.wallmode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceCameraLifecycleTest {
    @Test
    fun `camera runs only while configured display is ambient`() {
        assertTrue(shouldRunPresenceCamera(true, true, true))
        assertFalse(shouldRunPresenceCamera(true, true, false))
        assertFalse(shouldRunPresenceCamera(true, false, true))
        assertFalse(shouldRunPresenceCamera(false, true, true))
    }
}
