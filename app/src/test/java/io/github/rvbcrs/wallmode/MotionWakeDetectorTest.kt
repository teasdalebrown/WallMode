package io.github.rvbcrs.wallmode

import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionWakeDetectorTest {
    @Test
    fun warmsUpThenIgnoresExposureChangesButDetectsLocalMovement() {
        val detector = MotionWakeDetector()
        val still = ByteArray(WIDTH * HEIGHT) { 60 }
        val brighter = ByteArray(WIDTH * HEIGHT) { 90.toByte() }
        val movement = brighter.copyOf().apply {
            for (y in 7 until 16) {
                for (x in 9 until 23) this[y * WIDTH + x] = 150.toByte()
            }
        }

        repeat(8) { index ->
            val startupFrame = if (index in 2..5) movement else still
            assertFalse(detector.detect(startupFrame.asBuffer(), WIDTH, HEIGHT, WIDTH, 1))
        }
        assertFalse(detector.detect(brighter.asBuffer(), WIDTH, HEIGHT, WIDTH, 1))
        assertFalse(detector.detect(movement.asBuffer(), WIDTH, HEIGHT, WIDTH, 1))
        assertTrue(detector.detect(movement.asBuffer(), WIDTH, HEIGHT, WIDTH, 1))
    }

    @Test
    fun proximityRequiresFarBeforeEachNearWake() {
        val gate = ProximityWakeGate()

        assertFalse(gate.update(near = true))
        assertFalse(gate.update(near = false))
        assertTrue(gate.update(near = true))
        assertFalse(gate.update(near = true))
        assertFalse(gate.update(near = false))
        assertTrue(gate.update(near = true))
        gate.reset()
        assertFalse(gate.update(near = true))
    }

    private fun ByteArray.asBuffer() = ByteBuffer.wrap(this)

    private companion object {
        const val WIDTH = 32
        const val HEIGHT = 24
    }
}
