package io.github.rvbcrs.wallmode

import org.junit.Assert.*
import org.junit.Test

class BannerNoticeTest {
    @Test fun validatesDefaultsAndAllLevels() {
        val notice = BannerNotice.validated("laundry", " Wasmachine ", " Klaar ")!!
        assertEquals("Wasmachine", notice.title)
        assertEquals("Klaar", notice.message)
        assertEquals(10, notice.seconds)
        assertEquals(BannerLevel.INFO, notice.level)
        assertFalse(notice.sound)
        assertNull(notice.action)
        BannerLevel.entries.forEach {
            assertEquals(it, BannerNotice.validated("notice", "", "Ready", level = it.name.lowercase())!!.level)
        }
        listOf(3, 120).forEach {
            assertEquals(it, BannerNotice.validated("notice", "", "Ready", seconds = it)!!.seconds)
        }
        assertEquals(ActionCardAction("confirm", "Confirmed"),
            BannerNotice.validated("notice", "", "Ready", sound = true,
                action = ActionCardAction("confirm", " Confirmed "))!!.action)
    }

    @Test fun rejectsInvalidTextIdsAndLimits() {
        listOf("", "../bad", "bad id", "x".repeat(65)).forEach {
            assertNull(BannerNotice.validated(it, "", "Ready"))
        }
        listOf("", " ", "two\nlines", "bad\u0085text", "x".repeat(301), "😀".repeat(151)).forEach {
            assertNull(BannerNotice.validated("notice", "", it))
        }
        assertNull(BannerNotice.validated("notice", "x".repeat(81), "Ready"))
        assertNull(BannerNotice.validated("notice", "", "Ready", level = "danger"))
        listOf(2, 121, Int.MAX_VALUE).forEach {
            assertNull(BannerNotice.validated("notice", "", "Ready", seconds = it))
        }
        listOf(ActionCardAction("bad/id", "Ok"), ActionCardAction("ok", ""),
            ActionCardAction("ok", "x".repeat(25))).forEach {
            assertNull(BannerNotice.validated("notice", "", "Ready", action = it))
        }
        // Treated as literal text by native TextViews, never interpreted as HTML or a URL.
        assertNotNull(BannerNotice.validated("notice", "", "<b>Ready</b> & \"café\""))
    }
}
