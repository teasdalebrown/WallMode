package io.github.rvbcrs.wallmode

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech

private const val MAX_ANNOUNCEMENT_LENGTH = 255

internal data class Announcement(val text: String, val volume: Int)

internal fun validatedAnnouncement(text: String, volume: Int): Announcement? {
    val normalized = text.trim()
    return Announcement(normalized, volume)
        .takeIf {
            normalized.length in 1..MAX_ANNOUNCEMENT_LENGTH &&
                normalized.none(Char::isISOControl) && volume in 0..100
        }
}

internal class AnnouncementSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private var textToSpeech: TextToSpeech? = null
    private var ready = false
    private var initializing = false
    private var closed = false
    private var pending: Announcement? = null
    private var utteranceSequence = 0L

    fun announce(text: String, volume: Int): Boolean {
        val announcement = validatedAnnouncement(text, volume) ?: return false
        if (closed) return false
        pending = announcement
        val engine = textToSpeech
        if (ready && engine != null) {
            speakPending(engine)
        } else if (!initializing) {
            initialize()
        }
        return true
    }

    fun stop() {
        pending = null
        if (ready) textToSpeech?.stop()
    }

    fun shutdown() {
        if (closed) return
        closed = true
        pending = null
        ready = false
        initializing = false
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private fun initialize() {
        initializing = true
        textToSpeech = TextToSpeech(appContext) { status ->
            initializing = false
            val engine = textToSpeech
            if (closed || status != TextToSpeech.SUCCESS || engine == null) {
                pending = null
                engine?.shutdown()
                textToSpeech = null
                return@TextToSpeech
            }
            ready = true
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .build()
            )
            speakPending(engine)
        }
    }

    private fun speakPending(engine: TextToSpeech) {
        val announcement = pending ?: return
        pending = null
        engine.speak(
            announcement.text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, announcement.volume / 100f)
            },
            "wallmode-announcement-${++utteranceSequence}"
        )
    }
}
