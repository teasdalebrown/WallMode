package io.github.rvbcrs.wallmode

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

internal sealed interface PulseWakeTrialEvent {
    data class Ready(val detail: String) : PulseWakeTrialEvent
    data class Detected(val probability: Float, val wakeWord: String) : PulseWakeTrialEvent
    data object SpeechStarted : PulseWakeTrialEvent
    data class AudioCaptured(val samples: ShortArray, val endReason: PulseCaptureEndReason, val postWakeSamples: Int, val silenceSamples: Int) : PulseWakeTrialEvent
    data object NoSpeech : PulseWakeTrialEvent
    data class StopDetected(val epoch: Long) : PulseWakeTrialEvent
    data class Failed(val detail: String) : PulseWakeTrialEvent
}

/**
 * Local alongside-Hey-Pulse persona trial. This class never retains audio and has
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
        private val MODELS = listOf("annabel", "morris")
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1_280 // 80 ms
        private const val PROBABILITY_CUTOFF = 0.80f
        private const val SLIDING_WINDOW_SIZE = 3
        private const val COOLDOWN_INFERENCES = 34 // roughly two seconds
        private const val RESUME_COOLDOWN_INFERENCES = 8
        private const val PRE_WAKE_SAMPLES = (SAMPLE_RATE * 1.5f).toInt()
        private const val MAX_POST_WAKE_SAMPLES = PulseCaptureEndpoint.MAX_POST_WAKE_SAMPLES
        private const val MAX_CAPTURE_SAMPLES = PRE_WAKE_SAMPLES + MAX_POST_WAKE_SAMPLES
        private const val SPEECH_LEVEL = 520
    }

    private val applicationContext = context.applicationContext
    private val frontend = MicroFrontend()
    private data class Detector(
        val name: String, val interpreter: Interpreter, val inputFrames: Int,
        val inputScale: Float, val inputZeroPoint: Int,
        val outputScale: Float, val outputZeroPoint: Int,
        val input: ByteBuffer, val output: ByteBuffer,
        val pendingFrames: ArrayDeque<FloatArray> = ArrayDeque(),
        val recentScores: ArrayDeque<Int> = ArrayDeque()
    )
    private val detectors = mutableListOf<Detector>()
    private var stopDetector: PulseStopDetector? = null
    @Volatile private var speechInterruptionEnabled = false
    private val acousticStopGate = PulseAcousticStopGate()
    private val preWakeSamples = ArrayDeque<Short>(PRE_WAKE_SAMPLES)
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var captureSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var captureThread: Thread? = null
    private var cooldownUntilMillis = 0L
    @Volatile private var capturingCommand = false
    @Volatile private var detectionSuspended = false
    private val commandSamples = ArrayList<Short>(MAX_CAPTURE_SAMPLES)
    private var commandSpeechStarted = false
    private var commandSpeechChunks = 0
    private var commandSpeechVisible = false
    private var commandSilenceSamples = 0
    private var commandPostWakeSamples = 0

    init {
        MODELS.forEach(::loadModel)
        runCatching {
            stopDetector = PulseStopDetector(applicationContext)
            Log.i(TAG, "Loaded local Stop model with Waveshare cutoff 170/255 and window 5")
        }
            .onFailure { onEvent(PulseWakeTrialEvent.Failed("Local Stop model could not load: ${it.message}")) }
    }

    private fun loadModel(name: String) {
        val asset = "$name.tflite"
        try {
            val descriptor = applicationContext.assets.openFd(asset)
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
            val inputQuant = input.quantizationParams()
            val outputQuant = output.quantizationParams()
            require(input.dataType() == org.tensorflow.lite.DataType.INT8 &&
                output.dataType() == org.tensorflow.lite.DataType.UINT8) { "Unsupported wake tensor types" }
            detectors += Detector(name, loaded, shape[1], inputQuant.scale, inputQuant.zeroPoint,
                outputQuant.scale, outputQuant.zeroPoint,
                ByteBuffer.allocateDirect(shape[1] * MicroFrontend.FEATURE_SIZE).order(ByteOrder.nativeOrder()),
                ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder()))
            Log.i(TAG, "Loaded $asset inputFrames=${shape[1]} " +
                "gate=${if (name == "hey_pulse") "existing0.80/window3" else "native>204/window3"}")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to load wake model $asset", error)
            onEvent(PulseWakeTrialEvent.Failed("$asset could not load: ${error.message}"))
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (MODELS.any { required -> detectors.none { it.name == required } } || stopDetector == null || !frontend.isInitialized) {
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
            captureSource,
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
        onEvent(PulseWakeTrialEvent.Ready("Local detection active: ${detectors.joinToString { it.name }}"))
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
                    if (speechInterruptionEnabled) processStopChunk(chunk)
                    else if (capturingCommand) processCommandChunk(chunk) else processChunk(chunk)
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
    fun setSpeechInterruptionEnabled(enabled: Boolean) {
        acousticStopGate.invalidate()
        stopDetector?.reset()
        speechInterruptionEnabled = enabled
    }

    @Synchronized
    fun setAssistantPlaybackActive(token: Any, active: Boolean, spokenText: String? = null) {
        acousticStopGate.playback(token, active, spokenText)
        stopDetector?.reset()
    }

    fun acceptsAcousticStop(epoch: Long): Boolean = acousticStopGate.accepts(epoch)

    @Synchronized
    fun stopDiagnostic(): String =
        "capture_source=$captureSource stop_armed=${speechInterruptionEnabled && acousticStopGate.allowed()} playback_suppressed=${!acousticStopGate.allowed()} scores=${stopDetector?.lastTriggerScores?.joinToString(",")} " +
        "aec_available=${AcousticEchoCanceler.isAvailable()} " +
        "aec_created=${echoCanceler != null} aec_enabled=${echoCanceler?.enabled} " +
        "ns_created=${noiseSuppressor != null} ns_enabled=${noiseSuppressor?.enabled}"

    @Synchronized
    private fun processStopChunk(chunk: ShortArray) {
        if (speechInterruptionEnabled && acousticStopGate.allowed() && stopDetector?.accepts(chunk) == true) {
            speechInterruptionEnabled = false
            onEvent(PulseWakeTrialEvent.StopDetected(acousticStopGate.detectionEpoch()))
        }
    }

    @Synchronized
    private fun processChunk(chunk: ShortArray) {
        chunk.forEach { sample ->
            preWakeSamples.addLast(sample)
            if (preWakeSamples.size > PRE_WAKE_SAMPLES) preWakeSamples.removeFirst()
        }
        // One frontend/microphone, independent frame queues and recurrent states.
        for (frame in frontend.processSamples(chunk)) {
            for (detector in detectors) {
                detector.pendingFrames.addLast(frame)
                if (detector.pendingFrames.size < detector.inputFrames) continue
                val input = detector.input; val output = detector.output
                input.rewind()
                repeat(detector.inputFrames) {
                    detector.pendingFrames.removeFirst().forEach { value ->
                        val quantized = if (detector.name == "hey_pulse")
                            (value / detector.inputScale).roundToInt() + detector.inputZeroPoint
                        else pulsePersonaFeatureQuantize((value * 25.6f).roundToInt())
                        input.put(quantized.coerceIn(-128, 127).toByte())
                    }
                }
                input.rewind(); output.rewind()
                detector.interpreter.run(input, output)
                output.rewind()
                handleScore(detector, output.get().toInt() and 0xff)
                if (capturingCommand) return
            }
        }
    }

    private fun handleScore(detector: Detector, raw: Int) {
        if (detectionSuspended) return
        detector.recentScores.addLast(raw)
        while (detector.recentScores.size > SLIDING_WINDOW_SIZE) detector.recentScores.removeFirst()
        if (detector.recentScores.size < SLIDING_WINDOW_SIZE ||
            SystemClock.elapsedRealtime() < cooldownUntilMillis) return
        val average = detector.recentScores.average().toFloat()
        val probability = (average - detector.outputZeroPoint) * detector.outputScale
        val accepts = if (detector.name == "hey_pulse") probability >= PROBABILITY_CUTOFF
            else pulsePersonaWakeAccepts(detector.recentScores.toList())
        if (accepts) {
            val cooldownMs = if (detector.name == "hey_pulse")
                COOLDOWN_INFERENCES * detector.inputFrames * MicroFrontend.STEP_SIZE_MS else 2_000
            cooldownUntilMillis = SystemClock.elapsedRealtime() + cooldownMs
            detectors.forEach { it.recentScores.clear() }
            beginCommandCapture()
            Log.i(TAG, "${detector.name} detected probability=$probability")
            onEvent(PulseWakeTrialEvent.Detected(probability, detector.name))
        }
    }

    private var chatCapture = false

    @Synchronized
    private fun beginCommandCapture(includePreWake: Boolean = true, chat: Boolean = false) {
        chatCapture = chat
        acousticStopGate.invalidate()
        capturingCommand = true
        detectionSuspended = true
        commandSamples.clear()
        if (includePreWake) commandSamples.addAll(preWakeSamples)
        commandSpeechStarted = false
        commandSpeechChunks = 0
        commandSpeechVisible = false
        commandSilenceSamples = 0
        commandPostWakeSamples = 0
    }

    @Synchronized
    private fun processCommandChunk(chunk: ShortArray) {
        commandSamples.ensureCapacity((commandSamples.size + chunk.size).coerceAtMost(MAX_CAPTURE_SAMPLES))
        for (sample in chunk) {
            if (commandSamples.size >= MAX_CAPTURE_SAMPLES) break
            commandSamples.add(sample)
        }
        commandPostWakeSamples += chunk.size
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
        PulseCaptureEndpoint.endReason(commandPostWakeSamples, commandSpeechStarted, commandSilenceSamples, chatCapture)
            ?.let(::finishCommandCapture)
    }

    private fun finishCommandCapture(reason: PulseCaptureEndReason) {
        capturingCommand = false
        detectionSuspended = true
        if (reason == PulseCaptureEndReason.NO_SPEECH) {
            commandSamples.clear()
            onEvent(PulseWakeTrialEvent.NoSpeech)
        } else {
            val captured = ShortArray(commandSamples.size) { commandSamples[it] }
            commandSamples.clear()
            onEvent(PulseWakeTrialEvent.AudioCaptured(captured, reason, commandPostWakeSamples, commandSilenceSamples))
        }
        commandSpeechStarted = false
        commandSpeechChunks = 0
        commandSpeechVisible = false
        commandSilenceSamples = 0
        commandPostWakeSamples = 0
        detectors.forEach { it.recentScores.clear() }
        cooldownUntilMillis = SystemClock.elapsedRealtime() + 2_000
    }

    @Synchronized
    fun resumeDetection(): Boolean {
        if (!running.get()) return false
        acousticStopGate.invalidate()
        val remainingCooldown = cooldownUntilMillis
        // Command capture/playback suspends frontend input. Start fresh session
        // state instead of replaying a stale wake activation on return to idle.
        resetDetector()
        detectionSuspended = false
        detectors.forEach { it.recentScores.clear() }
        cooldownUntilMillis = maxOf(remainingCooldown, SystemClock.elapsedRealtime() + RESUME_COOLDOWN_INFERENCES * 20)
        return true
    }

    @Synchronized
    fun suspendDetection() {
        capturingCommand = false
        commandSamples.clear()
        detectionSuspended = true
        detectors.forEach { it.recentScores.clear() }
    }

    @Synchronized
    fun captureCommandWithoutWake(chat: Boolean = false): Boolean {
        if (!running.get() || capturingCommand) return false
        beginCommandCapture(includePreWake = false, chat = chat)
        return true
    }

    @Synchronized
    private fun resetDetector() {
        frontend.reset()
        // Variable-tensor reset failed exact parity in the retained scorer.
        // Fresh instances establish session state; never reset between wake calls.
        detectors.forEach { it.interpreter.close() }; detectors.clear()
        MODELS.forEach(::loadModel)
        cooldownUntilMillis = 0L
        detectionSuspended = false
        capturingCommand = false
        commandSamples.clear()
        commandSpeechStarted = false
        commandSpeechChunks = 0
        commandSpeechVisible = false
        commandSilenceSamples = 0
        commandPostWakeSamples = 0
        preWakeSamples.clear()
    }

    fun setCommunicationCapture(enabled: Boolean, resume: Boolean): Boolean {
        stop()
        captureSource = if (enabled) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        if (!resume) return true
        start()
        return running.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
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
        commandPostWakeSamples = 0
        resetDetector()
    }

    override fun close() {
        stop()
        detectors.forEach { it.interpreter.close() }
        detectors.clear()
        frontend.close()
        stopDetector?.close()
    }
}
