package com.genie.tts.speech

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import com.genie.tts.character.CharacterPackSchema
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class OwnedTensor(
    val shape: LongArray,
    val type: OnnxJavaType,
    val bytes: ByteBuffer,
) {
    fun toTensor(env: OrtEnvironment): OnnxTensor {
        val view = bytes.duplicate().order(ByteOrder.nativeOrder())
        view.position(0)
        view.limit(view.capacity())
        return OnnxTensor.createTensor(env, view, shape, type)
    }

    companion object {
        fun fromFile(file: File, dtype: String, shape: LongArray): OwnedTensor {
            val expected = CharacterPackSchema.tensorBytes(dtype, shape)
            val type = when (dtype) {
                "float32" -> OnnxJavaType.FLOAT
                "int64" -> OnnxJavaType.INT64
                else -> error("不支持的 dtype: $dtype")
            }
            val bytes = readDirect(file)
            if (bytes.capacity().toLong() != expected) {
                error("${file.name} 大小是 ${bytes.capacity()} 字节，按 shape 应为 $expected")
            }
            return OwnedTensor(shape, type, bytes)
        }

        fun fromLongs(shape: LongArray, values: LongArray): OwnedTensor {
            val copy = ByteBuffer.allocateDirect(values.size * 8).order(ByteOrder.nativeOrder())
            values.forEach { copy.putLong(it) }
            copy.flip()
            return OwnedTensor(shape, OnnxJavaType.INT64, copy)
        }

        fun fromFloats(shape: LongArray, values: FloatArray): OwnedTensor {
            val copy = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            values.forEach { copy.putFloat(it) }
            copy.flip()
            return OwnedTensor(shape, OnnxJavaType.FLOAT, copy)
        }

        fun fromOutput(tensor: OnnxTensor): OwnedTensor {
            val info = tensor.info as TensorInfo
            if (info.type == OnnxJavaType.BOOL) {
                val bit: Byte = if (tensor.value == true) 1 else 0
                val copy = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
                copy.put(bit)
                copy.flip()
                return OwnedTensor(info.shape.copyOf(), OnnxJavaType.BOOL, copy)
            }
            val src = tensor.byteBuffer.duplicate().order(ByteOrder.nativeOrder())
            src.position(0)
            val copy = ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.nativeOrder())
            copy.put(src)
            copy.flip()
            return OwnedTensor(info.shape.copyOf(), info.type, copy)
        }

        private fun readDirect(file: File): ByteBuffer {
            FileInputStream(file).channel.use { channel ->
                val buffer = ByteBuffer.allocateDirect(channel.size().toInt()).order(ByteOrder.nativeOrder())
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) break
                }
                buffer.flip()
                return buffer
            }
        }
    }
}
