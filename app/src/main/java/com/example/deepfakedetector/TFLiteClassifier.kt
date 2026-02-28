package com.example.deepfakedetector

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TFLiteClassifier(context: Context) {
    private val interpreter: Interpreter
    val inputShape: IntArray

    init {
        val modelBytes = context.assets.open("deepfake_model.tflite").readBytes()
        val buffer = ByteBuffer.allocateDirect(modelBytes.size)
        buffer.order(ByteOrder.nativeOrder())
        buffer.put(modelBytes)
        buffer.rewind()

        val options = Interpreter.Options().apply { setNumThreads(4) }
        interpreter = Interpreter(buffer, options)

        inputShape = interpreter.getInputTensor(0).shape()
        Log.e("TFLITE", "Input tensor shape = ${inputShape.contentToString()}")

        // Sanity check: random input
        try {
            val total = inputShape.fold(1) { acc, i -> acc * i }  // ✅ FIXED
            val randBuf = ByteBuffer.allocateDirect(total * 4).order(ByteOrder.nativeOrder())
            repeat(total) { randBuf.putFloat(((Math.random() * 2) - 1).toFloat()) }
            randBuf.rewind()
            val out = Array(1) { FloatArray(1) }
            interpreter.run(randBuf, out)
            Log.e("TFLITE", "Random input logit = ${out[0][0]}")
        } catch (e: Exception) {
            Log.e("TFLITE", "Random test failed", e)
        }
    }

    fun predict(input: ByteBuffer): Float {
        val output = Array(1) { FloatArray(1) }
        interpreter.run(input, output)
        return output[0][0]
    }

    fun close() {
        interpreter.close()
    }
}
