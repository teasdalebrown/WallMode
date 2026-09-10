package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientImageLoaderTest {
    @Test
    fun samplesLargeImagesToAtMost1280Pixels() {
        assertEquals(1, ambientImageSampleSize(1280, 720))
        assertEquals(2, ambientImageSampleSize(1281, 720))
        assertEquals(4, ambientImageSampleSize(2561, 1440))
        assertEquals(4, ambientImageSampleSize(4000, 3000))
    }
}
