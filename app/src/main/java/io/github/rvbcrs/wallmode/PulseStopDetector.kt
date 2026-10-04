package io.github.rvbcrs.wallmode

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/** The same local Stop model, frontend and cutoff as the Waveshare endpoints. */
internal class PulseStopDetector(context: Context) : AutoCloseable {
    private val frontend = MicroFrontend()
    private val interpreter: Interpreter
    private val frames = ArrayDeque<FloatArray>()
    private val scores = ArrayDeque<Float>()
    private val input: ByteBuffer
    private val output: ByteBuffer
    private val frameCount: Int
    private val inputScale: Float
    private val inputZero: Int
    private val outputScale: Float
    private val outputZero: Int
    private val inputUnsigned: Boolean
    private val outputUnsigned: Boolean

    init {
        val descriptor = context.assets.openFd("stop.tflite")
        val model = FileInputStream(descriptor.fileDescriptor).channel.use {
            it.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
        }
        descriptor.close()
        interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(1) })
        interpreter.allocateTensors()
        val tensor = interpreter.getInputTensor(0)
        val shape = tensor.shape()
        require(shape.size == 3 && shape[0] == 1 && shape[2] == MicroFrontend.FEATURE_SIZE)
        frameCount = shape[1]
        inputScale = tensor.quantizationParams().scale
        inputUnsigned = tensor.dataType() == DataType.UINT8
        require(inputUnsigned || tensor.dataType() == DataType.INT8)
        inputZero = tensor.quantizationParams().zeroPoint
        val result = interpreter.getOutputTensor(0)
        outputUnsigned = result.dataType() == DataType.UINT8
        require(outputUnsigned || result.dataType() == DataType.INT8)
        outputScale = result.quantizationParams().scale
        outputZero = result.quantizationParams().zeroPoint
        input = ByteBuffer.allocateDirect(frameCount * MicroFrontend.FEATURE_SIZE).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
    }

    var lastTriggerScores: List<Float> = emptyList()
        private set

    fun reset() {
        lastTriggerScores = emptyList()
        frontend.reset()
        interpreter.resetVariableTensors()
        frames.clear()
        scores.clear()
    }

    fun accepts(samples: ShortArray): Boolean {
        frames.addAll(frontend.processSamples(samples))
        while (frames.size >= frameCount) {
            input.rewind()
            repeat(frameCount) {
                frames.removeFirst().forEach { value ->
                    val quantized = (value / inputScale).roundToInt() + inputZero
                    input.put((if (inputUnsigned) quantized.coerceIn(0, 255) else quantized.coerceIn(-128, 127)).toByte())
                }
            }
            input.rewind()
            output.rewind()
            interpreter.run(input, output)
            output.rewind()
            val raw = output.get().toInt()
            scores.addLast(((if (outputUnsigned) raw and 255 else raw) - outputZero) * outputScale)
            while (scores.size > 5) scores.removeFirst()
            if (scores.size == 5 && scores.average() >= 170.0 / 255.0) {
                lastTriggerScores = scores.toList()
                return true
            }
        }
        return false
    }

    override fun close() { interpreter.close(); frontend.close() }
}
