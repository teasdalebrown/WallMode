package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientWeatherClientTest {
    @Test
    fun mapsRepresentativeWmoCodes() {
        assertEquals("Clear", AmbientWeatherClient.conditionFor(0))
        assertEquals("Rain", AmbientWeatherClient.conditionFor(63))
        assertEquals("Thunderstorms", AmbientWeatherClient.conditionFor(95))
    }
}
