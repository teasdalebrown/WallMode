package io.github.rvbcrs.wallmode

import org.junit.Assert.*
import org.junit.Test

class PulseQuestionDeliveryTest {
    @Test fun fullAnswerPassagesReachOneSpeechRequestWithoutLoss() {
        val delivery = PulseQuestionDelivery()
        val parts = listOf("First explanation.", "Second explanation.", "Numbers are twenty-four degrees.")
        parts.forEach(delivery::chunk)
        delivery.finish(parts.joinToString(" "))
        assertEquals(parts.joinToString(" "), delivery.speech())
    }

    @Test fun completeJsonFallbackIsNotSilent() {
        val delivery = PulseQuestionDelivery()
        delivery.finish("Complete answer.")
        assertEquals("Complete answer.", delivery.speech())
    }

    @Test fun companionAnswerDoesNotReplaceStreamedSpeech() {
        val delivery = PulseQuestionDelivery()
        delivery.chunk("Spoken summary."); delivery.finish("Stored full explanation.")
        assertEquals("Spoken summary.", delivery.speech())
        assertEquals("Stored full explanation.", delivery.answer)
    }

    @Test(expected = IllegalStateException::class)
    fun missingCompletionCannotBecomePartialSuccess() {
        val delivery = PulseQuestionDelivery(); delivery.chunk("Partial")
        delivery.speech()
    }
}
