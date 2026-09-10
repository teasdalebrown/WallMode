package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientClockDotsTest {
    @Test
    fun secondDotFlashesFromDarkToBrightAndBack() {
        assertEquals(0f, secondDotFlashIntensity(0), 0.001f)
        assertEquals(1f, secondDotFlashIntensity(500_000_000), 0.001f)
        assertEquals(0f, secondDotFlashIntensity(999_999_999), 0.001f)
    }

    @Test
    fun dotsStartAtTopAndRunClockwiseAroundRoundedFrame() {
        val dots = FloatArray(120)
        fillRoundedRectClockDots(dots, 0f, 0f, 200f, 100f, 20f)

        fun assertDot(index: Int, x: Float, y: Float) {
            assertEquals(x, dots[index * 2], 0.001f)
            assertEquals(y, dots[index * 2 + 1], 0.001f)
        }

        assertDot(0, 100f, 0f)
        assertDot(15, 200f, 50f)
        assertDot(30, 100f, 100f)
        assertDot(45, 0f, 50f)
        dots.forEachIndexed { index, value ->
            assertTrue(if (index % 2 == 0) value in 0f..200f else value in 0f..100f)
        }
    }
}
