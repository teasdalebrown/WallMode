package io.github.rvbcrs.wallmode

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

internal class PulseVoiceCancellation {
    @Volatile private var cancelled = false
    private val connections = mutableSetOf<HttpURLConnection>()
    fun check() { if (cancelled) throw CancellationException("Voice request cancelled") }
    @Synchronized fun register(connection: HttpURLConnection) {
        check()
        connections.add(connection)
    }
    @Synchronized fun release(connection: HttpURLConnection) { connections.remove(connection) }
    @Synchronized fun cancel() {
        cancelled = true
        connections.forEach { it.disconnect() }
        connections.clear()
    }
}

internal data class PulseVoiceResult(
    val transcript: String,
    val response: String,
    val ok: Boolean,
    val ignored: Boolean = false,
    val wakeVerified: Boolean = false,
    val error: String = "",
    val continueListening: Boolean = false
)

internal data class PulseSpeechAudio(val url: String, val waitCue: Boolean = false, val progressCue: Boolean = false, val preparationCue: Boolean = false)

internal class PulseVoiceClient(
    private val endpointId: String = "honor_endpoint",
    private val room: String = "Hall/Kitchen",
    private val bridgeUrl: String = "http://192.168.4.211:3065/api/bridge"
) {
    @Volatile private var detectedWakeWord = "hey_pulse"
    fun detectedWake(word: String) {
        require(word in setOf("hey_pulse", "annabel", "morris"))
        detectedWakeWord = word
        closeConversation()
    }
    @Volatile private var chatSessionUntilMillis = 0L
    @Volatile private var promptedFollowupUntilMillis = 0L
    fun closeConversation() { chatSessionUntilMillis = 0L; promptedFollowupUntilMillis = 0L }
    fun openPromptedFollowup() { promptedFollowupUntilMillis = System.currentTimeMillis() + 18_000L }
    private val requestCancellation = ThreadLocal<PulseVoiceCancellation>()

    fun process(samples: ShortArray, cancellation: PulseVoiceCancellation = PulseVoiceCancellation(), onTranscript: (String) -> Unit = {}, onSpeechChunk: (PulseSpeechAudio) -> Unit): PulseVoiceResult {
        requestCancellation.set(cancellation)
        try {
            cancellation.check()
            val result = processRequest(samples, onTranscript) { wav -> cancellation.check(); onSpeechChunk(wav) }
            cancellation.check()
            return result
        } finally {
            requestCancellation.remove()
        }
    }

    private fun speechSource(text: String, waitCue: Boolean = false) =
        PulseSpeechAudio(pulseSpeechUrl(bridgeUrl, text, endpointId, waitCue), waitCue,
            preparationCue = waitCue && detectedWakeWord == "morris" && text in MORRIS_PREPARATION_CUES)

    /** Only the endpoint's text-ready/TTS-pending handover may offer this cue. */
    fun progressCue(): PulseSpeechAudio? = if (detectedWakeWord == "morris")
        PulseSpeechAudio(pulseSpeechUrl(bridgeUrl, MORRIS_PROGRESS_CUE, endpointId, true),
            waitCue = true, progressCue = true) else null

    private fun processRequest(samples: ShortArray, onTranscript: (String) -> Unit, onSpeechChunk: (PulseSpeechAudio) -> Unit): PulseVoiceResult {
        val stt = postBytes(pulseAudioPath(endpointId, detectedWakeWord), wavBytes(samples), "audio/wav")
        val rawTranscript = stt.optJSONObject("stt")?.optString("text").orEmpty().trim()
        val transcript = PulseWakePhrase.commandAfterDetectedWake(rawTranscript, detectedWakeWord)
        if (transcript.isBlank()) {
            return PulseVoiceResult(transcript, "", true, ignored = true, wakeVerified = true)
        }
        requestCancellation.get()?.check()
        onTranscript(transcript)
        // The on-device model has already verified the wake event. Pulse Core
        // owns the same command gate and response routes used by the physical
        // endpoints; do not maintain a second tablet-only routing policy here.
        val now = System.currentTimeMillis()
        val command = transcript.trim().lowercase()
        if (command == "chat") {
            chatSessionUntilMillis = now + CHAT_SESSION_MILLIS
            val response = "Chat is open. What would you like to talk about?"
            onSpeechChunk(speechSource(response))
            return PulseVoiceResult(transcript, response, true, wakeVerified = true, continueListening = true)
        }
        if (now < chatSessionUntilMillis && command in CHAT_CLOSE_COMMANDS) {
            chatSessionUntilMillis = 0L
            val response = "Chat closed."
            onSpeechChunk(speechSource(response))
            return PulseVoiceResult(transcript, response, true, wakeVerified = true)
        }
        val chatFollowup = now < chatSessionUntilMillis && !PulseCommandGate.accepts(transcript)
        val promptedFollowup = now < promptedFollowupUntilMillis && !PulseCommandGate.accepts(transcript)
        promptedFollowupUntilMillis = 0L
        val routedTranscript = if (chatFollowup) "chat $transcript" else if (promptedFollowup) "question $transcript" else transcript
        if (chatFollowup) chatSessionUntilMillis = now + CHAT_SESSION_MILLIS
        val payload = JSONObject().put("endpoint_id", endpointId).put("transcript", routedTranscript)
            .put("room", room).put("endpoint_room", room)
        if (pulseVoiceRoutePath(routedTranscript) == "/question-stream") {
            val result = processQuestionStream(routedTranscript, payload, onSpeechChunk)
            requestCancellation.get()?.check()
            val continueListening = result.continueListening || chatFollowup || routedTranscript.trim().lowercase().startsWith("chat ")
            if (chatFollowup || routedTranscript.trim().lowercase().startsWith("chat ")) chatSessionUntilMillis = System.currentTimeMillis() + CHAT_SESSION_MILLIS
            if (result.continueListening) promptedFollowupUntilMillis = System.currentTimeMillis() + 18_000L
            return result.copy(transcript = transcript, continueListening = continueListening)
        }
        val route = postJson("/transcript", payload)
        val ignored = route.optString("status") == "ignored" || route.optString("mode") == "command_gate"
        if (ignored) return PulseVoiceResult(transcript, "", true, ignored = true, wakeVerified = true)
        val response = spokenResponse(route)
        if (!route.optBoolean("ok", false)) {
            val error = route.optString("error", route.optString("reason", "Pulse could not complete that request"))
            return PulseVoiceResult(transcript, response.ifBlank { error }, false, wakeVerified = true, error = error)
        }
        if (response.isNotBlank()) onSpeechChunk(speechSource(response))
        return PulseVoiceResult(
            transcript,
            response,
            true,
            wakeVerified = true,
            continueListening = route.optBoolean("listen_again", false)
        )
    }

    private fun processQuestionStream(
        transcript: String,
        payload: JSONObject,
        onSpeechChunk: (PulseSpeechAudio) -> Unit
    ): PulseVoiceResult {
        val connection = URL("$bridgeUrl/question-stream").openConnection() as HttpURLConnection
        requestCancellation.get()?.register(connection)
        val delivery = PulseQuestionDelivery()
        var listenAgain = false
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/x-ndjson")
            val body = payload.toString().toByteArray()
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Pulse returned HTTP $code")
            // The shared route may return a complete JSON answer (Status,
            // local Pulse Life answers, notes), not only streamed NDJSON.
            if (!pulseResponseIsStream(connection.contentType.orEmpty())) {
                val route = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val ignored = route.optString("status") == "ignored" || route.optString("mode") == "command_gate"
                if (ignored) return PulseVoiceResult(transcript, "", true, ignored = true, wakeVerified = true)
                val spoken = spokenResponse(route)
                val ok = route.optBoolean("ok", false)
                val error = if (ok) "" else route.optString("error", "Pulse could not complete that request")
                if (spoken.isNotBlank()) onSpeechChunk(speechSource(spoken))
                return PulseVoiceResult(transcript, spoken, ok, wakeVerified = true,
                    error = error, continueListening = route.optBoolean("listen_again", false))
            }
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    requestCancellation.get()?.check()
                    if (line.isBlank()) return@forEach
                    val event = JSONObject(line)
                    when (event.optString("type")) {
                        "wait" -> {
                            val cue = event.optString("cue_text").trim()
                            if (cue.isNotBlank() && !delivery.finished)
                                onSpeechChunk(speechSource(cue, waitCue = true))
                        }
                        "chunk" -> delivery.chunk(event.optString("answer_chunk"))
                        "done" -> {
                            if (event.optString("status") == "ignored")
                                return PulseVoiceResult(transcript, "", true, ignored = true, wakeVerified = true)
                            if (!event.optBoolean("ok", true))
                                throw IllegalStateException(event.optString("error", "Question stream failed"))
                            delivery.finish(event.optString("answer"))
                            listenAgain = event.optBoolean("listen_again", false)
                        }
                        "error" -> throw IllegalStateException(event.optString("error", "Question stream failed"))
                    }
                }
            }
            val speech = delivery.speech()
            if (speech.isNotBlank()) onSpeechChunk(speechSource(speech))
            return PulseVoiceResult(transcript, delivery.answer, true, wakeVerified = true, continueListening = listenAgain)
        } finally {
            requestCancellation.get()?.release(connection)
            connection.disconnect()
        }
    }

    fun heartbeat() {
        postJson(
            "/endpoint/heartbeat",
            JSONObject().put("endpoint_id", endpointId).put("room", room).put("endpoint_room", room)
                .put("endpoint_type", "android_wall_tablet")
                .put("role", "pulse-wall-voice-endpoint").put("transport", "android-native")
                .put("wake_word_engine", "microWakeWord")
                .put("active_wake_words", JSONArray().put("annabel").put("morris").put("stop"))
                .put("capabilities", JSONArray().put("local-wake-word").put("local-stop-interruption").put("audio").put("transcript").put("tts").put("visual-response"))
        )
    }

    private fun spokenResponse(route: JSONObject): String {
        route.optString("answer").trim().takeIf { it.isNotBlank() }?.let { return it }
        route.optString("acknowledgement").trim().takeIf { it.isNotBlank() }?.let { return it }
        route.optString("confirmation").trim().takeIf { it.isNotBlank() }?.let { return it }
        val result = route.optJSONObject("result")
        result?.optString("confirmation")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        result?.optJSONObject("result")?.optString("confirmation")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        // A bare success flag is not confirmation that the requested action
        // completed. Never invent spoken confirmation here.
        return ""
    }

    private fun postJson(path: String, payload: JSONObject): JSONObject {
        val bytes = post(path, payload.toString().toByteArray(), "application/json")
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private fun postBytes(path: String, body: ByteArray, contentType: String): JSONObject =
        JSONObject(post(path, body, contentType).toString(Charsets.UTF_8))

    private fun post(path: String, body: ByteArray, contentType: String): ByteArray {
        val connection = URL("$bridgeUrl$path").openConnection() as HttpURLConnection
        requestCancellation.get()?.register(connection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw IllegalStateException("Pulse returned HTTP $code")
            requestCancellation.get()?.check()
            return response
        } finally {
            requestCancellation.get()?.release(connection)
            connection.disconnect()
        }
    }

    private fun getBytes(path: String): ByteArray {
        val connection = URL("$bridgeUrl$path").openConnection() as HttpURLConnection
        requestCancellation.get()?.register(connection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 150_000
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw IllegalStateException("Pulse speech returned HTTP $code")
            requestCancellation.get()?.check()
            return response
        } finally {
            requestCancellation.get()?.release(connection)
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun wavBytes(samples: ShortArray): ByteArray {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(pcm::putShort)
        val output = ByteArrayOutputStream(44 + pcm.array().size)
        fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) = output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        fun int16(value: Int) = output.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        ascii("RIFF"); int32(36 + pcm.array().size); ascii("WAVE")
        ascii("fmt "); int32(16); int16(1); int16(1); int32(16_000); int32(32_000); int16(2); int16(16)
        ascii("data"); int32(pcm.array().size); output.write(pcm.array())
        return output.toByteArray()
    }

}

internal fun pulseTtsPath(text: String, endpointId: String): String =
    "/tts?text=${URLEncoder.encode(text, Charsets.UTF_8.name())}&endpoint_id=${URLEncoder.encode(endpointId, Charsets.UTF_8.name())}"

internal fun pulseResponseIsStream(contentType: String): Boolean =
    contentType.substringBefore(';').trim().equals("application/x-ndjson", ignoreCase = true)

internal fun pulseCompleteSpeechChunks(text: String): List<String> {
    val chunks = mutableListOf<String>()
    var pending = ""
    for (word in text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }) {
        if (pending.isNotBlank() && pending.length + word.length + 1 > 180) {
            chunks.add(pending); pending = ""
        }
        pending = listOf(pending, word).filter(String::isNotBlank).joinToString(" ")
        if (pending.length >= 100 && word.last() in ".!?") { chunks.add(pending); pending = "" }
    }
    if (pending.isNotBlank()) {
        if (chunks.isNotEmpty() && pending.length < 40 && chunks.last().length + pending.length + 1 <= 220) chunks[chunks.lastIndex] += " $pending"
        else chunks.add(pending)
    }
    return chunks
}

internal const val CHAT_SESSION_MILLIS = 10 * 60 * 1000L
private val CHAT_CLOSE_COMMANDS = setOf("stop", "end chat", "close chat", "exit chat", "stop chat", "goodbye")

private val QUESTION_STREAM_DOMAIN = Regex(
    "^\\s*(?:question|chat|news|lookup|look\\s+up|search|research|verify|reason|think|status)\\b",
    RegexOption.IGNORE_CASE
)

internal fun pulseVoiceRoutePath(transcript: String): String =
    if (pulseIdentityRequest(transcript)) "/transcript"
    else if (QUESTION_STREAM_DOMAIN.containsMatchIn(transcript)) "/question-stream"
    else "/transcript"

private fun pulseIdentityRequest(transcript: String): Boolean {
    var clean = transcript.trim().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    if (clean.startsWith("question ")) clean = clean.removePrefix("question ").trim()
    if (clean.startsWith("and ")) clean = clean.removePrefix("and ").trim()
    return clean in setOf(
        "who are you", "what is your name", "whats your name", "your name",
        "introduce yourself", "introduce your self", "please introduce yourself",
        "please introduce your self"
    )
}

internal fun pulseAudioPath(endpointId: String, wakeWord: String): String =
    "/audio?endpoint_id=${URLEncoder.encode(endpointId, Charsets.UTF_8.name())}" +
        "&wake_word=${URLEncoder.encode(wakeWord, Charsets.UTF_8.name())}"

/** Cached cues cannot trigger inference or fallback; normal answers still stream. */
internal fun pulseSpeechUrl(bridgeUrl: String, text: String, endpointId: String, waitCue: Boolean): String =
    "$bridgeUrl${pulseTtsPath(text, endpointId)}&" + if (waitCue) "cache_only=true" else "stream=true"

internal const val MORRIS_PROGRESS_CUE = "Right… I’ve got it here."
internal val MORRIS_PREPARATION_CUES = setOf(
    "Ah… hello… hang on… I’ll have to check that first.",
    "Ah… right… give me a moment to think that through."
)
