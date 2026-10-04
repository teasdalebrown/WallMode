package io.github.rvbcrs.wallmode

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** PCM WAV transport, including a live WAV with an unknown final length. */
internal class PulseStreamPlayer(private val silentProof: Boolean = false, private val optionalCue: Boolean = false) {
    private val playbackGate = PulsePlaybackGate()
    val hasStarted get() = playbackGate.hasStarted
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var diagnosticSampleRate = 0
    @Volatile private var diagnosticBytesWritten = 0L

    fun playbackDiagnostic(): String {
        val frames = track?.playbackHeadPosition?.toLong()?.and(0xffffffffL)
        return "rate=$diagnosticSampleRate played_frames=$frames written_frames=${diagnosticBytesWritten / 2}"
    }

    fun play(url: String, started: () -> Unit, completed: (Int) -> Unit, failed: (Throwable) -> Unit, beforeStart: () -> Unit = {}, fixedDebugWav: ByteArray? = null) {
        Thread({
            try {
                val source = if (fixedDebugWav != null) {
                    java.io.ByteArrayInputStream(fixedDebugWav)
                } else {
                    val http = URL(url).openConnection() as HttpURLConnection
                    connection = http
                    http.connectTimeout = if (optionalCue) 800 else 15_000
                    http.readTimeout = if (optionalCue) 800 else 150_000
                    checkActive()
                    http.inputStream
                }
                checkActive()
                DataInputStream(source).use { input ->
                    val header = ByteArray(12).also(input::readFully)
                    require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Unsupported speech audio" }
                    var rate = 0
                    var remaining = -1L
                    while (true) {
                        val chunk = ByteArray(8).also(input::readFully)
                        val length = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong() and 0xffffffffL
                        val id = String(chunk, 0, 4, Charsets.US_ASCII)
                        if (id == "data") { remaining = length; break }
                        require(length <= 65_536) { "Oversized WAV metadata" }
                        val payload = ByteArray(length.toInt()).also(input::readFully)
                        if (id == "fmt ") {
                            val fmt = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                            require(payload.size >= 16 && fmt.getShort(0).toInt() == 1 &&
                                fmt.getShort(2).toInt() == 1 && fmt.getShort(14).toInt() == 16) { "Expected mono PCM16 speech" }
                            rate = fmt.getInt(4)
                        }
                        if (length % 2 != 0L) input.readByte()
                    }
                    require(rate in 8_000..48_000)
                    val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val player = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, rate * 2)).build()
                    diagnosticSampleRate = rate
                    track = player
                    if (silentProof) player.setVolume(0f)
                    checkActive()
                    var bytesWritten = 0L
                    var supplyUnderruns = 0
                    val buffer = ByteArray(8192)
                    var playing = false
                    while (remaining > 0) {
                        checkActive()
                        var count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) {
                            require(remaining == 0xffffffffL - bytesWritten) { "Speech ended prematurely" }
                            break
                        }
                        if (count == 0) continue
                        if (count % 2 != 0) {
                            val last = input.read()
                            require(last >= 0) { "Incomplete PCM sample" }
                            buffer[count++] = last.toByte()
                        }
                        var offset = 0
                        while (offset < count) {
                            checkActive()
                            val written = player.write(buffer, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                            check(written > 0) { "Speech audio output failed: $written" }
                            offset += written
                            bytesWritten += written
                            diagnosticBytesWritten = bytesWritten
                        }
                        if (!playing) { beforeStart(); playbackGate.start { player.play() }; playing = true; started() }
                        supplyUnderruns = player.underrunCount
                        remaining -= count
                    }
                    require(bytesWritten > 0 && bytesWritten % 2 == 0L)
                    // Network EOF is not playback completion: drain the speaker
                    // before allowing the microphone's follow-up window to open.
                    val deadline = System.nanoTime() + 20_000_000_000L
                    while ((player.playbackHeadPosition.toLong() and 0xffffffffL) < bytesWritten / 2) {
                        checkActive()
                        check(System.nanoTime() < deadline) { "Speech playback did not drain" }
                        Thread.sleep(20)
                    }
                    checkActive()
                    // An empty buffer after the final sample is the natural
                    // end, not a mid-speech supply underrun.
                    completed(supplyUnderruns)
                }
            } catch (error: Throwable) {
                if (!playbackGate.cancelled) failed(error)
            } finally {
                connection?.disconnect()
                connection = null
                val player = track
                track = null
                runCatching { player?.stop() }
                runCatching { player?.release() }
            }
        }, "PulseSpeechStream").start()
    }

    private fun checkActive() = playbackGate.checkActive()
    fun stop() {
        playbackGate.cancel {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
        }
        connection?.disconnect()
    }
    fun release() = stop()
}
