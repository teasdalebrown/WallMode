package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientBackgroundModeTest {
    @Test
    fun validatesDirectHttpMediaUrls() {
        assertTrue(KioskPreferences.isValidAmbientMediaUrl("http://192.168.0.248/clouds.mp4"))
        assertTrue(KioskPreferences.isValidAmbientMediaUrl("https://example.com/background.png"))
        assertFalse(KioskPreferences.isValidAmbientMediaUrl("file:///sdcard/background.png"))
        assertFalse(KioskPreferences.isValidAmbientMediaUrl("https://user:secret@example.com/image.jpg"))
        assertFalse(KioskPreferences.isValidAmbientMediaUrl("https://example.com/" + "x".repeat(2_048)))
    }

    @Test
    fun unknownStoredModeFallsBackToBuiltIn() {
        assertEquals(AmbientBackgroundMode.BUILT_IN, AmbientBackgroundMode.fromStoredValue("PLAYLIST"))
    }

    @Test
    fun onlyRemoteBackgroundsRequireAUrl() {
        assertFalse(AmbientBackgroundMode.BUILT_IN.requiresUrl)
        assertFalse(AmbientBackgroundMode.BUNDLED_IMAGE.requiresUrl)
        assertFalse(AmbientBackgroundMode.BUNDLED_VIDEO.requiresUrl)
        assertFalse(AmbientBackgroundMode.IMMICH_ALBUM.requiresUrl)
        assertTrue(AmbientBackgroundMode.IMAGE_URL.requiresUrl)
        assertTrue(AmbientBackgroundMode.VIDEO_URL.requiresUrl)
    }
}
