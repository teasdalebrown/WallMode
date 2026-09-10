package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientPaletteTest {
    @Test
    fun accentsReactToContextAndStayReadable() {
        assertEquals(0xffffa32b.toInt(), AmbientPalette.forWeather(0, isDay = true))
        assertEquals(0xff35b4f5.toInt(), AmbientPalette.forWeather(63, isDay = true))
        assertEquals(0xff9774ff.toInt(), AmbientPalette.forWeather(0, isDay = false))

        val fallback = AmbientPalette.forWeather(null, isDay = true)
        val pixels = intArrayOf(0xff000060.toInt(), 0xff000060.toInt(), 0x00000000)
        val first = AmbientPalette.fromPixels(pixels, fallback)
        val second = AmbientPalette.fromPixels(pixels, fallback)

        assertEquals(first, second)
        assertTrue(luminance(first) >= 112)
        assertTrue((first and 0xff) > (first ushr 16 and 0xff))
        assertEquals(fallback, AmbientPalette.fromPixels(intArrayOf(0xff777777.toInt()), fallback))
    }

    private fun luminance(color: Int): Int =
        (54 * (color ushr 16 and 0xff) + 183 * (color ushr 8 and 0xff) + 19 * (color and 0xff)) / 256
}
