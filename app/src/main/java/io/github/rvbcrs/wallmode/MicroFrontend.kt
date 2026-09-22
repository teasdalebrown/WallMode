package io.github.rvbcrs.wallmode

/**
 * JNI wrapper around TensorFlow Lite Micro's fixed-point micro_speech frontend.
 * Successive chunks must be 16 kHz mono PCM16. The native implementation owns
 * the overlapping 30 ms window and emits one 40-bin feature frame every 10 ms.
 */
internal class MicroFrontend(
    sampleRate: Int = SAMPLE_RATE,
    stepSizeMs: Int = STEP_SIZE_MS
) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val STEP_SIZE_MS = 10
        const val FEATURE_SIZE = 40

        init {
            System.loadLibrary("microfrontend")
        }

        @JvmStatic private external fun nativeCreate(sampleRate: Int, stepSizeMs: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeProcessSamples(
            handle: Long,
            samples: ShortArray
        ): ArrayList<FloatArray>?
        @JvmStatic private external fun nativeReset(handle: Long)
    }

    private var handle = nativeCreate(sampleRate, stepSizeMs)

    val isInitialized: Boolean
        get() = handle != 0L

    fun processSamples(samples: ShortArray): List<FloatArray> =
        if (handle == 0L) emptyList() else nativeProcessSamples(handle, samples).orEmpty()

    fun reset() {
        if (handle != 0L) nativeReset(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }
}
