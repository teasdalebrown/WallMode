package io.github.rvbcrs.wallmode

internal object AmbientPalette {
    fun forWeather(code: Int?, isDay: Boolean): Int = if (isDay) {
        when (code) {
            0 -> rgb(255, 163, 43)
            1, 2 -> rgb(77, 205, 207)
            3, 45, 48 -> rgb(90, 176, 196)
            in 51..67, in 80..82 -> rgb(53, 180, 245)
            in 71..77, in 85..86 -> rgb(191, 229, 244)
            in 95..99 -> rgb(184, 112, 255)
            else -> rgb(47, 196, 201)
        }
    } else {
        when (code) {
            in 51..67, in 80..82 -> rgb(101, 137, 255)
            in 71..77, in 85..86 -> rgb(172, 207, 255)
            in 95..99 -> rgb(192, 105, 255)
            3, 45, 48 -> rgb(126, 143, 207)
            else -> rgb(151, 116, 255)
        }
    }

    fun fromPixels(pixels: IntArray, fallback: Int): Int {
        var redTotal = 0L
        var greenTotal = 0L
        var blueTotal = 0L
        var weightTotal = 0L

        for (pixel in pixels) {
            if (pixel ushr 24 < 128) continue
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            val brightest = maxOf(red, green, blue)
            if (brightest < 24) continue
            val chroma = brightest - minOf(red, green, blue)
            val weight = (24 + chroma).toLong()
            redTotal += red * weight
            greenTotal += green * weight
            blueTotal += blue * weight
            weightTotal += weight
        }

        if (weightTotal == 0L) return readable(fallback)
        val color = rgb(
            (redTotal / weightTotal).toInt(),
            (greenTotal / weightTotal).toInt(),
            (blueTotal / weightTotal).toInt()
        )
        return if (chroma(color) < 18) readable(fallback) else readable(color)
    }

    private fun luminance(color: Int): Int {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return (54 * red + 183 * green + 19 * blue) / 256
    }

    private fun readable(color: Int): Int {
        val current = luminance(color)
        if (current >= MIN_LUMINANCE) return color or OPAQUE
        val blend = ((MIN_LUMINANCE - current) * 255 + (254 - current)) / (255 - current)
        val red = blendWithWhite(color ushr 16 and 0xff, blend)
        val green = blendWithWhite(color ushr 8 and 0xff, blend)
        val blue = blendWithWhite(color and 0xff, blend)
        return rgb(red, green, blue)
    }

    private fun chroma(color: Int): Int {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return maxOf(red, green, blue) - minOf(red, green, blue)
    }

    private fun blendWithWhite(channel: Int, amount: Int): Int =
        channel + ((255 - channel) * amount + 127) / 255

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        OPAQUE or (red shl 16) or (green shl 8) or blue

    private const val MIN_LUMINANCE = 112
    private const val OPAQUE = -0x1000000
}
