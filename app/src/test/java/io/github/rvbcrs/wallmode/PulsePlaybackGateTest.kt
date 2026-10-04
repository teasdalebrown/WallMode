package io.github.rvbcrs.wallmode

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PulsePlaybackGateTest {
    @Test fun stopBeforeFirstSamplePreventsHardwarePlay() {
        val gate = PulsePlaybackGate()
        gate.cancel {}
        var played = false
        try { gate.start { played = true }; fail("Expected cancellation") }
        catch (_: CancellationException) {}
        assertFalse(played)
        assertFalse(gate.hasStarted)
    }

    @Test fun startedAndStopShareHardwareOrdering() {
        val gate = PulsePlaybackGate()
        val playing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val events = mutableListOf<String>()
        val start = Thread { gate.start { events.add("play"); playing.countDown(); release.await() } }
        start.start()
        assertTrue(playing.await(2, TimeUnit.SECONDS))
        val stop = Thread { gate.cancel { events.add("stop"); stopped.countDown() } }
        stop.start()
        assertFalse(stopped.await(50, TimeUnit.MILLISECONDS))
        release.countDown(); start.join(2000); stop.join(2000)
        assertEquals(listOf("play", "stop"), events)
        assertTrue(gate.cancelled)
        assertTrue(gate.hasStarted)
    }
}
