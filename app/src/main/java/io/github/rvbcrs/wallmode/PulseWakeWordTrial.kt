package io.github.rvbcrs.wallmode

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

internal sealed interface PulseWakeTrialEvent {
    data class Ready(val detail: String) : PulseWakeTrialEvent
    data class Detected(val probability: Float) : PulseWakeTrialEvent
    data object SpeechStarted : PulseWakeTrialEvent
    data class AudioCaptured(val samples: ShortArray) : PulseWakeTrialEvent
    data object NoSpeech : PulseWakeTrialEvent
    data class Failed(val detail: String) : PulseWakeTrialEvent
}

/**
 * Phase-one, local-only Hey Pulse proof. This class never retains audio and has
 * no network code. It feeds the production Pulse microWakeWord model from the
 * tablet microphone and reports only readiness, detection probability or an
 * actionable local failure.
 */
internal class PulseWakeWordTrial(
    context: Context,
    private val onEvent: (PulseWakeTrialEvent) -> Unit
) : AutoCloseable {
    companion object {
        private const val TAG = "PulseWakeWordTrial"
        private const val MODEL = "hey_pulse.tflite"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1_280 // 80 ms
        private const val PROBABILITY_CUTOFF = 0.71f
        private const val SLIDING_WINDOW_SIZE = 3
        private const val COOLDOWN_INFERENCES = 34 // roughly two seconds
        private const val MAX_CAPTURE_SAMPLES = SAMPLE_RATE * 7
        private const val SPEECH_START_TIMEOUT_SAMPLES = SAMPLE_RATE * 3
        private const val SILENCE_END_SAMPLES = (SAMPLE_RATE * 1.15f).toInt()
        private const val SPEECH_LEVEL = 520
    }

    private val applicationContext = context.applicationContext
    private val frontend = MicroFrontend()
    private var interpreter: Interpreter? = null
    private var inputFrames = 0
    private var inputScale = 1f
    private var inputZeroPoint = 0
    private var outputScale = 1f
    private var outputZeroPoint = 0
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private val pendingFrames = ArrayDeque<FloatArray>()
    private val recentScores = ArrayDeque<Float>()
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var captureThread: Thread? = null
    private var cooldown = 0
    @Volatile private var capturingCommand = false
    private val commandSamples = ArrayList<Short>(MAX_CAPTURE_SAMPLES)
    private var commandSpeechStarted = false
    private var commandSpeechChunks = 0
    private var commandSpeechVisible = false
    private var commandSilenceSamples = 0

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val descriptor = applicationContext.assets.openFd(MODEL)
            val model = FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
            val loaded = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
            loaded.allocateTensors()
            val input = loaded.getInputTensor(0)
            val output = loaded.getOutputTensor(0)
            val shape = input.shape()
            require(shape.size == 3 && shape[0] == 1 && shape[2] == MicroFrontend.FEATURE_SIZE) {
                "Unexpected model input shape ${shape.joinToString(prefix = "[", postfix = "]")}" 
            }
            inputFrames = shape[1]
            val inputQuant = input.quantizationParams()
            inputScale = inputQuant.scale
            inputZeroPoint = inputQuant.zeroPoint
            val outputQuant = output.quantizationParams()
            outputScale = outputQuant.scale
            outputZeroPoint = outputQuant.zeroPoint
            inputBuffer = ByteBuffer.allocateDirect(inputFrames * MicroFrontend.FEATURE_SIZE)
                .order(ByteOrder.nativeOrder())
            outputBuffer = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
            interpreter = loaded
            Log.i(TAG, "Loaded $MODEL inputFrames=$inputFrames cutoff=$PROBABILITY_CUTOFF")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to load wake model", error)
            onEvent(PulseWakeTrialEvent.Failed("Wake model could not load: ${error.message}"))
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (interpreter == null || !frontend.isInitialized) {
            running.set(false)
            onEvent(PulseWakeTrialEvent.Failed("Wake model is unavailable"))
            return
        }
        resetDetector()
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, CHUNK_SAMPLES * 4)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            running.set(false)
            onEvent(PulseWakeTrialEvent.Failed("Tablet microphone could not initialise"))
            return
        }
        audioRecord = recorder
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.also { it.enabled = true }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true }
            }
        }.onFailure { Log.w(TAG, "Optional audio effects unavailable", it) }

        try {
            recorder.startRecording()
        } catch (error: Exception) {
            stop()
            onEvent(PulseWakeTrialEvent.Failed("Microphone recording failed: ${error.message}"))
            return
        }
        onEvent(PulseWakeTrialEvent.Ready("Local Hey Pulse detection active"))
        captureThread = Thread({ captureLoop(recorder) }, "pulse-wake-trial").also { it.start() }
    }

    private fun captureLoop(recorder: AudioRecord) {
        val chunk = ShortArray(CHUNK_SAMPLES)
        try {
            while (running.get()) {
                var filled = 0
                while (running.get() && filled < chunk.size) {
                    val count = recorder.read(chunk, filled, chunk.size - filled)
                    if (count > 0) filled += count
                    else if (count < 0) throw IllegalStateException("AudioRecord error $count")
                }
                if (filled == chunk.size) {
                    if (capturingCommand) processCommandChunk(chunk) else processChunk(chunk)
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "Capture loop failed", error)
                onEvent(PulseWakeTrialEvent.Failed("Wake listener stopped: ${error.message}"))
            }
        }
    }

    @Synchronized
    private fun processChunk(chunk: ShortArray) {
        val model = interpreter ?: return
        val input = inputBuffer ?: return
        val output = outputBuffer ?: return
        pendingFrames.addAll(frontend.processSamples(chunk))
        while (pendingFrames.size >= inputFrames) {
            input.rewind()
            repeat(inputFrames) {
                val frame = pendingFrames.removeFirst()
                frame.forEach { value ->
                    val quantized = (value / inputScale).roundToInt() + inputZeroPoint
                    input.put(quantized.coerceIn(-128, 127).toByte())
                }
            }
            input.rewind()
            output.rewind()
            model.run(input, output)
            output.rewind()
            val raw = output.get().toInt() and 0xff
            handleScore((raw - outputZeroPoint) * outputScale)
        }
    }

    private fun handleScore(score: Float) {
        if (cooldown > 0) cooldown--
        recentScores.addLast(score)
        while (recentScores.size > SLIDING_WINDOW_SIZE) recentScores.removeFirst()
        if (recentScores.size < SLIDING_WINDOW_SIZE || cooldown > 0) return
        val average = recentScores.average().toFloat()
        if (average >= PROBABILITY_CUTOFF) {
            cooldown = COOLDOWN_INFERENCES
            recentScores.clear()
            beginCommandCapture()
            Log.i(TAG, "Hey Pulse detected probability=$average")
            onEvent(PulseWakeTrialEvent.Detected(average))
        }
    }

    @Synchronized
    private fun beginCommandCapture() {
        capturingCommand = true
        commandSamples.clear()
        commandSpeechStarted = false
        commandSpeechChunks = 0
        commandSpeechVisible = false
        commandSilenceSamples = 0
    }

    @Synchronized
    private fun processCommandChunk(chunk: ShortArray) {
        commandSamples.ensureCapacity((commandSamples.size + chunk.size).coerceAtMost(MAX_CAPTURE_SAMPLES))
        for (sample in chunk) {
            if (commandSamples.size >= MAX_CAPTURE_SAMPLES) break
            commandSamples.add(sample)
        }
        val meanAbsoluteLevel = chunk.sumOf { kotlin.math.abs(it.toInt()) }.toDouble() / chunk.size
        if (meanAbsoluteLevel >= SPEECH_LEVEL) {
            commandSpeechStarted = true
            commandSpeechChunks++
            commandSilenceSamples = 0
            if (!commandSpeechVisible && commandSpeechChunks >= 2) {
                commandSpeechVisible = true
                onEvent(PulseWakeTrialEvent.SpeechStarted)
            }
        } else if (commandSpeechStarted) {
            commandSilenceSamples += chunk.size
        }
        val timedOutWaiting = !commandSpeechStarted && commandSamples.size >= SPEECH_START_TIMEOUT_SAMPLES
        val utteranceEnded = commandSpeechStarted && commandSilenceSamples >= SILENCE_END_SAMPLES
        val captureFull = commandSamples.size >= MAX_CAPTURE_SAMPLES
        if (timedOutWaiting || utteranceEnded || captureFull) finishCommandCapture(timedOutWaiting)
    }

    private fun finishCommandCapture(noSpeech: Boolean) {
        capturingCommand = false
        if (noSpeech) {
            commandSamples.clear()
            onEvent(PulseWakeTrialEvent.NoSpeech)
        } else {
            val captured = ShortArray(commandSamples.size) { commandSamples[it] }
            commandSamples.clear()
            onEvent(PulseWakeTrialEvent.AudioCaptured(captured))
        }
        resetDetector()
        cooldown = COOLDOWN_INFERENCES
    }

    @Synchronized
    private fun resetDetector() {
        frontend.reset()
        interpreter?.resetVariableTensors()
        pendingFrames.clear()
        recentScores.clear()
        cooldown = 0
        capturingCommand = false
        commandSamples.clear()
        commandSpeechStarted = false
        commandSpeechChunks = 0
        commandSpeechVisible = false
        commandSilenceSamples = 0
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { audioRecord?.stop() }
        captureThread?.join(1_000)
        captureThread = null
        runCatching { noiseSuppressor?.release() }
        runCatching { echoCanceler?.release() }
        runCatching { audioRecord?.release() }
        noiseSuppressor = null
        echoCanceler = null
        audioRecord = null
    }

    @Synchronized
    fun cancelCommandCapture() {
        if (!capturingCommand) return
        capturingCommand = false
        commandSamples.clear()
        commandSpeechStarted = false
        commandSpeechChunks = 0
        commandSpeechVisible = false
        commandSilenceSamples = 0
        resetDetector()
    }

    override fun close() {
        stop()
        interpreter?.close()
        interpreter = null
        frontend.close()
    }
}
