package io.github.rvbcrs.wallmode

import org.junit.Assert.*
import org.junit.Test

class PulseChatEntryTest {
    private fun expiry(client: PulseVoiceClient): Long =
        client.javaClass.getDeclaredField("chatSessionUntilMillis").apply {
            isAccessible = true
        }.getLong(client)

    @Test fun `bare chat punctuation uses local opening and retains raw transcript`() {
        for (persona in listOf("morris", "annabel")) {
            for (transcript in listOf("chat", "chat.", "CHAT!", "Chat?", " chat... ", "Chat?!")) {
                val client = PulseVoiceClient(endpointId = "fixture", bridgeUrl = "http://blocked.invalid")
                client.detectedWake(persona)
                val chunks = mutableListOf<PulseSpeechAudio>()
                val result = client.openBareChatIfRequested(transcript, 1000L, chunks::add)
                assertNotNull(transcript, result)
                assertEquals(transcript, result!!.transcript)
                assertEquals(if (persona == "morris") "Okay… I have a bit of time to spare. What’s up?"
                    else "Great… let’s have a natter.", result.response)
                assertTrue(client.chatIsActive(1000L))
                assertFalse(client.chatIsActive(601000L))
                assertTrue(result.ok)
                assertTrue(result.wakeVerified)
                assertTrue(result.continueListening)
                assertFalse(result.ignored)
                assertFalse(result.response.contains("Pulse"))
                assertEquals(601000L, expiry(client))
                assertEquals(1, chunks.size)
                assertEquals(result.response, chunks.single().spokenText)
                assertFalse(chunks.single().waitCue)
                assertFalse(chunks.single().progressCue)
                assertTrue(chunks.single().url.contains("endpoint_id=fixture"))
                assertEquals(persona, client.javaClass.getDeclaredField("detectedWakeWord").apply {
                    isAccessible = true
                }.get(client))
            }
        }
    }

    @Test fun `payload chat and other modes do not open bare chat or emit speech`() {
        for (transcript in listOf("chat about charging", "Chat. Explain charging", "chatty", "shout",
            "Maurice, shout.", "Morris Chat", "Question chat", "Question why is the fan on",
            "Lookup charging news", "Turn off office fan", "", "chat,", "chat:")) {
            val client = PulseVoiceClient()
            val result = client.openBareChatIfRequested(transcript, 1000L) {
                fail("Unexpected local Chat acknowledgement for $transcript")
            }
            assertNull(transcript, result)
            assertEquals(0L, expiry(client))
        }
        assertFalse(PulseCommandGate.accepts("shout"))
        assertFalse(PulseCommandGate.accepts("Maurice, shout."))
        assertEquals("/question-stream", pulseVoiceRoutePath("Chat. Explain charging"))
        assertEquals("/question-stream", pulseVoiceRoutePath("Question why is the fan on"))
        assertEquals("/question-stream", pulseVoiceRoutePath("Lookup charging news"))
        assertEquals("/transcript", pulseVoiceRoutePath("Turn off office fan"))
    }
}
