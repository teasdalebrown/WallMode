package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttContractTest {
    @Test
    fun notificationTemplateEncodesTheWholeMessageAsJson() {
        assertEquals("{{ {'text': value} | to_json }}", MqttContract.ANNOUNCEMENT_COMMAND_TEMPLATE)
        assertNull(MqttContract.validatedAnnouncementCommand("x".repeat(256), null))
        assertNull(MqttContract.validatedAnnouncementCommand("two\nlines", null))
    }

    @Test
    fun parsesAndValidatesAnnouncements() {
        assertEquals(
            WallModeMqttCommand.Announce("Welkom thuis", 80),
            MqttContract.validatedAnnouncementCommand(" Welkom thuis ", null)
        )
        assertEquals(
            WallModeMqttCommand.Announce("Deur open", 35),
            MqttContract.validatedAnnouncementCommand("Deur open", 35)
        )
        assertEquals(
            WallModeMqttCommand.StopAnnouncement,
            MqttContract.parseCommand("stop_announcement", "PRESS", retained = false)
        )
        assertNull(MqttContract.validatedAnnouncementCommand("", null))
        assertNull(MqttContract.validatedAnnouncementCommand("Hallo", 101))
        assertNull(MqttContract.parseCommand("announce", "{}", retained = true))
        assertNull(MqttContract.parseCommand("stop_announcement", "PRESS", retained = true))
    }

    @Test
    fun validatesBrokerAndBuildsTransportUri() {
        assertTrue(MqttContract.isValidBroker("192.168.0.2", 1883))
        assertTrue(MqttContract.isValidBroker("homeassistant.local", 8883))
        assertFalse(MqttContract.isValidBroker("tcp://192.168.0.2", 1883))
        assertFalse(MqttContract.isValidBroker("host/path", 1883))
        assertFalse(MqttContract.isValidBroker("host", 0))
        assertEquals("tcp://192.168.0.2:1883", MqttContract.serverUri("192.168.0.2", 1883, false))
        assertEquals("ssl://homeassistant.local:8883", MqttContract.serverUri("homeassistant.local", 8883, true))
    }

    @Test
    fun rejectsRetainedAndInvalidCommands() {
        assertNull(MqttContract.parseCommand("reload", "PRESS", retained = true))
        assertNull(MqttContract.parseCommand("reload", "wrong", retained = false))
        assertNull(MqttContract.parseCommand("ambient_brightness", "4", retained = false))
        assertNull(MqttContract.parseCommand("open_url", "file:///secret", retained = false))
        assertNull(MqttContract.parseCommand("open_url", "https://user:pass@example.com", retained = false))
        assertNull(MqttContract.parseCommand("banner", "{}", retained = true))
        assertEquals(
            WallModeMqttCommand.SetAmbientBrightness(25),
            MqttContract.parseCommand("ambient_brightness", "25", retained = false)
        )
    }

    @Test
    fun rejectsOversizedPayloadBeforeCommandDecoding() {
        assertTrue(MqttContract.isPayloadSizeAllowed(ByteArray(8_192)))
        assertFalse(MqttContract.isPayloadSizeAllowed(ByteArray(8_193)))
        assertNull(MqttContract.parseCommand("reload", "x".repeat(2_049), retained = false))
    }

    @Test
    fun publishedPageDropsCredentialsQueryAndFragment() {
        assertEquals(
            "https://example.com:8123/dashboard/main",
            MqttContract.safePage("https://user:secret@example.com:8123/dashboard/main?token=abc#view")
        )
        assertEquals("wallmode_c0ffee12abcd3456", MqttContract.deviceId("c0ffee12abcd3456"))
    }

    @Test
    fun validatesAtomicEventTakeovers() {
        assertEquals(
            WallModeMqttCommand.ShowTakeover(
                id = "front-door",
                url = "http://192.168.0.248:3124/?d=doorbell",
                seconds = 30,
                priority = 50
            ),
            MqttContract.validatedTakeover(
                action = "show",
                id = "front-door",
                url = "http://192.168.0.248:3124/?d=doorbell",
                seconds = null,
                priority = null
            )
        )
        assertEquals(
            WallModeMqttCommand.ClearTakeover("front-door"),
            MqttContract.validatedTakeover("clear", "front-door", null, null, null)
        )
        assertNull(MqttContract.validatedTakeover("show", "bad id", "https://example.com", 30, 50))
        assertNull(MqttContract.validatedTakeover("show", "alarm", "file:///secret", 30, 50))
        assertNull(MqttContract.validatedTakeover("show", "alarm", "https://example.com", 4, 50))
        assertNull(MqttContract.validatedTakeover("show", "alarm", "https://example.com", 30, 101))
    }

    @Test
    fun validatesNativeActionCardsWithoutChangingUrlTakeovers() {
        val actions = listOf(
            ActionCardAction("close", "Sluiten"),
            ActionCardAction("dismiss", "Negeren"),
            ActionCardAction("details", "Details")
        )
        assertEquals(
            WallModeMqttCommand.ShowActionCard(
                id = "garage_open",
                title = "Garagedeur staat open",
                message = "Wil je hem nu sluiten?",
                actions = actions,
                seconds = MqttContract.DEFAULT_TAKEOVER_SECONDS,
                priority = MqttContract.DEFAULT_TAKEOVER_PRIORITY
            ),
            MqttContract.validatedActionCardTakeover(
                id = "garage_open",
                title = "Garagedeur staat open",
                message = "Wil je hem nu sluiten?",
                actions = actions,
                seconds = null,
                priority = null
            )
        )
        assertEquals(
            WallModeMqttCommand.ShowTakeover("door", "https://example.com/door", 45, 60),
            MqttContract.validatedTakeover("show", "door", "https://example.com/door", 45, 60)
        )
    }

    @Test
    fun rejectsUnsafeOrMalformedActionCards() {
        val validAction = listOf(ActionCardAction("ok", "OK"))
        val invalidCards = listOf(
            MqttContract.validatedActionCardTakeover("card", "", "", validAction, 30, 50),
            MqttContract.validatedActionCardTakeover("card", "Title", "", emptyList(), 30, 50),
            MqttContract.validatedActionCardTakeover(
                "card",
                "Title",
                "",
                List(4) { ActionCardAction("action$it", "Action") },
                30,
                50
            ),
            MqttContract.validatedActionCardTakeover(
                "card",
                "Title",
                "",
                listOf(ActionCardAction("same", "A"), ActionCardAction("same", "B")),
                30,
                50
            ),
            MqttContract.validatedActionCardTakeover(
                "card",
                "Title",
                "",
                listOf(ActionCardAction("bad id", "A")),
                30,
                50
            ),
            MqttContract.validatedActionCardTakeover("bad id", "Title", "", validAction, 30, 50),
            MqttContract.validatedActionCardTakeover("card", "Title\n", "", validAction, 30, 50),
            MqttContract.validatedActionCardTakeover("card", "T".repeat(81), "", validAction, 30, 50),
            MqttContract.validatedActionCardTakeover("card", "Title", "M".repeat(301), validAction, 30, 50),
            MqttContract.validatedActionCardTakeover(
                "card",
                "Title",
                "",
                listOf(ActionCardAction("ok", "L".repeat(25))),
                30,
                50
            ),
            MqttContract.validatedActionCardTakeover("card", "Title", "", validAction, 4, 50),
            MqttContract.validatedActionCardTakeover("card", "Title", "", validAction, 30, 101)
        )
        invalidCards.forEach(::assertNull)
        assertNull(
            MqttContract.parseCommand(
                "takeover",
                """{"action":"show","id":"card","kind":"card"}""",
                retained = true
            )
        )
    }

    @Test
    fun buildsFixedNonSensitiveActionResponse() {
        assertEquals("event/action", MqttContract.ACTION_EVENT_SUFFIX)
        assertEquals("action", MqttContract.ACTION_EVENT_TYPE)
        assertEquals(
            """{"event_type":"action","card_id":"garage_open","action_id":"close","event_id":"12345678-1234-1234-1234-123456789abc"}""",
            MqttContract.actionResponsePayload(
                cardId = "garage_open",
                actionId = "close",
                eventId = "12345678-1234-1234-1234-123456789abc"
            )
        )
        assertNull(MqttContract.actionResponsePayload("bad id", "close", "12345678-1234-1234-1234-123456789abc"))
        assertNull(MqttContract.actionResponsePayload("card", "bad id", "12345678-1234-1234-1234-123456789abc"))
        assertNull(MqttContract.actionResponsePayload("card", "close", "not-an-event-id"))
        assertNull(MqttContract.actionResponsePayload("card\"", "close", "12345678-1234-1234-1234-123456789abc"))
        assertNull(MqttContract.actionResponsePayload("card", "x".repeat(33), "12345678-1234-1234-1234-123456789abc"))
        assertFalse(
            MqttContract.actionResponsePayload("card", "close", "12345678-1234-1234-1234-123456789abc") ==
                MqttContract.actionResponsePayload("card", "close", "87654321-4321-4321-4321-cba987654321")
        )
    }

    @Test
    fun normalizesBrokerIdentityWithoutWeakeningUsernameMatching() {
        assertEquals(
            MqttContract.brokerIdentity("HA.LOCAL", 1883, false, " wallmode "),
            MqttContract.brokerIdentity("ha.local", 1883, false, "wallmode")
        )
        assertFalse(
            MqttContract.brokerIdentity("ha.local", 1883, false, "WallMode") ==
                MqttContract.brokerIdentity("ha.local", 1883, false, "wallmode")
        )
    }

    @Test
    fun validatesProfileAndAmbientSceneCommands() {
        assertEquals(
            WallModeMqttCommand.OpenProfile(DashboardProfile.HOME),
            MqttContract.parseCommand("profile", MqttContract.PROFILE_HOME, retained = false)
        )
        assertEquals(
            WallModeMqttCommand.SetProfileSchedule(true),
            MqttContract.parseCommand("profile_schedule", "ON", retained = false)
        )
        assertEquals(
            WallModeMqttCommand.SetAmbientScene(AmbientScene.GLOW_CLOCK),
            MqttContract.parseCommand("ambient_scene", MqttContract.SCENE_GLOW_CLOCK, retained = false)
        )
        assertNull(MqttContract.parseCommand("profile", "Unknown", retained = false))
        assertNull(MqttContract.parseCommand("ambient_scene", MqttContract.SCENE_GLOW_CLOCK, retained = true))
    }
}
