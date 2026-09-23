package io.github.rvbcrs.wallmode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseCommandGateTest {
    @Test
    fun `approved short commands are routed`() {
        listOf("time", "Status", "blackout", "introduce yourself").forEach {
            assertTrue(it, PulseCommandGate.accepts(it))
        }
    }

    @Test
    fun `explicit command domains are routed`() {
        listOf(
            "news",
            "question tell me about Winston Churchill",
            "chat",
            "play David Bowie",
            "turn off hall main",
            "please news"
        ).forEach { assertTrue(it, PulseCommandGate.accepts(it)) }
    }

    @Test
    fun `ambient speech is rejected`() {
        listOf(
            "Please see the complete disclaimer at PissedConsumer.com",
            "Thank you very much",
            "you",
            "we should talk about this later",
            "playful conversation"
        ).forEach { assertFalse(it, PulseCommandGate.accepts(it)) }
    }

    @Test
    fun `fuzzy wake recovery is limited to harmless shorts`() {
        listOf("time", "date", "status").forEach {
            assertTrue(it, PulseCommandGate.acceptsFuzzyWake(it))
        }
        listOf("blackout", "play David Bowie", "turn off hall main", "question who was Churchill").forEach {
            assertFalse(it, PulseCommandGate.acceptsFuzzyWake(it))
        }
    }
}
