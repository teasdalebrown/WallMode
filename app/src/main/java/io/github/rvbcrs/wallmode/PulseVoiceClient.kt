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

internal class PulseVoiceClient(
    private val endpointId: String = "honor_endpoint",
    private val room: String = "Hall/Kitchen",
    private val bridgeUrl: String = "http://192.168.4.211:3065/api/bridge"
) {
    @Volatile private var chatSessionUntilMillis = 0L
    @Volatile private var promptedFollowupUntilMillis = 0L
    fun closeConversation() { chatSessionUntilMillis = 0L; promptedFollowupUntilMillis = 0L }
    fun openPromptedFollowup() { promptedFollowupUntilMillis = System.currentTimeMillis() + 18_000L }
    private val requestCancellation = ThreadLocal<PulseVoiceCancellation>()

    fun process(samples: ShortArray, cancellation: PulseVoiceCancellation = PulseVoiceCancellation(), onSpeechChunk: (ByteArray) -> Unit): PulseVoiceResult {
        requestCancellation.set(cancellation)
        try {
            cancellation.check()
            val result = processRequest(samples) { wav -> cancellation.check(); onSpeechChunk(wav) }
            cancellation.check()
            return result
        } finally {
            requestCancellation.remove()
        }
    }

    private fun processRequest(samples: ShortArray, onSpeechChunk: (ByteArray) -> Unit): PulseVoiceResult {
        val stt = postBytes("/audio?endpoint_id=${encode(endpointId)}", wavBytes(samples), "audio/wav")
        val rawTranscript = stt.optJSONObject("stt")?.optString("text").orEmpty().trim()
        val transcript = PulseWakePhrase.commandAfterDetectedWake(rawTranscript)
        if (transcript.isBlank()) {
            return PulseVoiceResult(transcript, "", true, ignored = true, wakeVerified = true)
        }
        // The on-device model has already verified the wake event. Pulse Core
        // owns the same command gate and response routes used by the physical
        // endpoints; do not maintain a second tablet-only routing policy here.
        val now = System.currentTimeMillis()
        val command = transcript.trim().lowercase()
        if (command == "chat") {
            chatSessionUntilMillis = now + CHAT_SESSION_MILLIS
            val response = "Chat is open. What would you like to talk about?"
            onSpeechChunk(getBytes(pulseTtsPath(response, endpointId)))
            return PulseVoiceResult(transcript, response, true, wakeVerified = true, continueListening = true)
        }
        if (now < chatSessionUntilMillis && command in CHAT_CLOSE_COMMANDS) {
            chatSessionUntilMillis = 0L
            val response = "Chat closed."
            onSpeechChunk(getBytes(pulseTtsPath(response, endpointId)))
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
        for (chunk in pulseCompleteSpeechChunks(response)) onSpeechChunk(getBytes(pulseTtsPath(chunk, endpointId)))
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
        onSpeechChunk: (ByteArray) -> Unit
    ): PulseVoiceResult {
        val connection = URL("$bridgeUrl/question-stream").openConnection() as HttpURLConnection
        requestCancellation.get()?.register(connection)
        var answer = ""
        var pendingSpeech = ""
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
                for (chunk in pulseCompleteSpeechChunks(spoken)) onSpeechChunk(getBytes(pulseTtsPath(chunk, endpointId)))
                return PulseVoiceResult(transcript, spoken, ok, wakeVerified = true,
                    error = error, continueListening = route.optBoolean("listen_again", false))
            }
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    requestCancellation.get()?.check()
                    if (line.isBlank()) return@forEach
                    val event = JSONObject(line)
                    when (event.optString("type")) {
                        "chunk" -> {
                            val chunk = event.optString("answer_chunk").trim()
                            if (chunk.isNotBlank()) {
                                answer = listOf(answer, chunk).filter(String::isNotBlank).joinToString(" ")
                                pendingSpeech = listOf(pendingSpeech, chunk).filter(String::isNotBlank).joinToString(" ")
                                if (pendingSpeech.length >= 100) {
                                    for (part in pulseCompleteSpeechChunks(pendingSpeech)) onSpeechChunk(getBytes(pulseTtsPath(part, endpointId)))
                                    pendingSpeech = ""
                                }
                            }
                        }
                        "done" -> {
                            answer = event.optString("answer", answer).trim()
                            listenAgain = event.optBoolean("listen_again", false)
                        }
                        "error" -> throw IllegalStateException(event.optString("error", "Question stream failed"))
                    }
                }
            }
            if (pendingSpeech.isNotBlank()) onSpeechChunk(getBytes(pulseTtsPath(pendingSpeech, endpointId)))
            return PulseVoiceResult(transcript, answer, true, wakeVerified = true, continueListening = listenAgain)
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
                .put("active_wake_words", JSONArray().put("hey_pulse").put("stop"))
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

private const val CHAT_SESSION_MILLIS = 5 * 60 * 1000L
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
