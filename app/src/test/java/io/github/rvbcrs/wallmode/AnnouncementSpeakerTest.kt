package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnouncementSpeakerTest {
    @Test
    fun validatesAndNormalizesAnnouncements() {
        assertEquals(Announcement("Hallo", 0), validatedAnnouncement("  Hallo  ", 0))
        assertEquals(Announcement("x".repeat(255), 100), validatedAnnouncement("x".repeat(255), 100))
        assertNull(validatedAnnouncement("   ", 50))
        assertNull(validatedAnnouncement("x".repeat(256), 50))
        assertNull(validatedAnnouncement("Hallo\nwereld", 50))
        assertNull(validatedAnnouncement("Hallo", -1))
        assertNull(validatedAnnouncement("Hallo", 101))
    }
}
