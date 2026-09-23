package io.github.rvbcrs.wallmode

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PulseVoiceResult(
    val transcript: String,
    val response: String,
    val ok: Boolean,
    val ignored: Boolean = false,
    val error: String = ""
)

internal class PulseVoiceClient(
    private val endpointId: String = "honor_endpoint",
    private val room: String = "Hall/Kitchen",
    private val bridgeUrl: String = "http://192.168.4.211:3065/api/bridge"
) {
    fun process(samples: ShortArray): Pair<PulseVoiceResult, ByteArray?> {
        val stt = postBytes("/audio?endpoint_id=${encode(endpointId)}", wavBytes(samples), "audio/wav")
        val rawTranscript = stt.optJSONObject("stt")?.optString("text").orEmpty().trim()
        val transcript = repairObservedWakePrefix(rawTranscript)
        if (transcript.isBlank()) {
            return PulseVoiceResult("", "I didn't hear a command.", false, error = "No speech was recognised") to null
        }
        val route = postJson(
            "/transcript",
            JSONObject().put("endpoint_id", endpointId).put("transcript", transcript)
                .put("room", room).put("endpoint_room", room)
        )
        val ignored = route.optString("status") == "ignored" || route.optString("mode") == "command_gate"
        if (ignored) return PulseVoiceResult(transcript, "", true, ignored = true) to null
        val response = spokenResponse(route)
        if (!route.optBoolean("ok", false)) {
            val error = route.optString("error", route.optString("reason", "Pulse could not complete that request"))
            return PulseVoiceResult(transcript, response.ifBlank { error }, false, error = error) to null
        }
        val speech = if (response.isBlank()) null else getBytes("/tts?text=${encode(response)}")
        return PulseVoiceResult(transcript, response, true) to speech
    }

    fun heartbeat() {
        postJson(
            "/endpoint/heartbeat",
            JSONObject().put("endpoint_id", endpointId).put("room", room).put("endpoint_room", room)
                .put("endpoint_type", "android_wall_tablet")
                .put("role", "pulse-wall-voice-endpoint").put("transport", "android-native")
                .put("wake_word_engine", "microWakeWord")
                .put("active_wake_words", JSONArray().put("hey_pulse"))
                .put("capabilities", JSONArray().put("local-wake-word").put("audio").put("transcript").put("tts").put("visual-response"))
        )
    }

    private fun spokenResponse(route: JSONObject): String {
        route.optString("answer").trim().takeIf { it.isNotBlank() }?.let { return it }
        route.optString("confirmation").trim().takeIf { it.isNotBlank() }?.let { return it }
        val result = route.optJSONObject("result")
        result?.optString("confirmation")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        result?.optJSONObject("result")?.optString("confirmation")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return if (route.optBoolean("ok", false)) "Done" else ""
    }

    private fun repairObservedWakePrefix(value: String): String {
        val compact = value.trim().replace(Regex("[.?!]+$"), "")
        val prefix = Regex("^(?:hey\\s+pulse|haypoles|paypost|hey\\s+polls|hey\\s+holes)\\s+(.+)$", RegexOption.IGNORE_CASE)
        return prefix.matchEntire(compact)?.groupValues?.get(1)?.trim() ?: value.trim()
    }

    private fun postJson(path: String, payload: JSONObject): JSONObject {
        val bytes = post(path, payload.toString().toByteArray(), "application/json")
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private fun postBytes(path: String, body: ByteArray, contentType: String): JSONObject =
        JSONObject(post(path, body, contentType).toString(Charsets.UTF_8))

    private fun post(path: String, body: ByteArray, contentType: String): ByteArray {
        val connection = URL("$bridgeUrl$path").openConnection() as HttpURLConnection
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
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun getBytes(path: String): ByteArray {
        val connection = URL("$bridgeUrl$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 30_000
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw IllegalStateException("Pulse speech returned HTTP $code")
            return response
        } finally {
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
