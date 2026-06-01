package com.example.bilitranscript

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android音频解码器
 * 将各种格式（m4a/mp3等）解码为16kHz单声道float PCM数据
 */
class AudioDecoder {

    companion object {
        private const val TAG = "AudioDecoder"
        private const val TARGET_SAMPLE_RATE = 16000  // sherpa-onnx需要16kHz
    }

    /**
     * 解码音频文件为16kHz单声道float数组
     * @param audioFile 音频文件
     * @param onProgress 解码进度回调 (0-1)
     */
    fun decodeToPcm(audioFile: File, onProgress: ((Float) -> Unit)? = null): FloatArray? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(audioFile.absolutePath)

            // 找到音频轨道
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                mime.startsWith("audio/")
            } ?: throw Exception("未找到音频轨道")

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)

            Log.d(TAG, "音频格式: $format")

            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // 创建解码器
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // 解码音频
            val pcmData = decodeAudio(extractor, codec, audioFile.length(), onProgress)

            codec.stop()
            codec.release()

            // 重采样到16kHz并转为单声道
            onProgress?.invoke(0.9f)
            val result = resampleTo16kHzMono(pcmData, sampleRate, channelCount)
            onProgress?.invoke(1f)
            result

        } catch (e: Exception) {
            Log.e(TAG, "音频解码失败: ${e.message}", e)
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * 使用MediaCodec解码音频
     * 使用ByteArrayOutputStream避免Short装箱导致的OOM
     */
    private fun decodeAudio(
        extractor: MediaExtractor,
        codec: MediaCodec,
        fileSize: Long,
        onProgress: ((Float) -> Unit)? = null
    ): ShortArray {
        // 用ByteArrayOutputStream累积原始字节，避免MutableList<Short>的巨额装箱开销
        val outputStream = java.io.ByteArrayOutputStream()

        val outputBufferInfo = MediaCodec.BufferInfo()

        var isInputDone = false
        var isOutputDone = false
        var inputBytes = 0L

        while (!isOutputDone) {
            // 输入数据
            if (!isInputDone) {
                val inputBufferId = codec.dequeueInputBuffer(10000)
                if (inputBufferId >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferId)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isInputDone = true
                    } else {
                        codec.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                        inputBytes += sampleSize
                        if (fileSize > 0) {
                            onProgress?.invoke((inputBytes.toFloat() / fileSize * 0.8f).coerceIn(0f, 0.8f))
                        }
                    }
                }
            }

            // 输出数据
            val outputBufferId = codec.dequeueOutputBuffer(outputBufferInfo, 10000)
            when {
                outputBufferId >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferId)!!
                    // 直接拷贝字节到输出流，避免创建中间ShortArray和List
                    val chunk = ByteArray(outputBufferInfo.size)
                    outputBuffer.get(chunk)
                    outputStream.write(chunk)
                    codec.releaseOutputBuffer(outputBufferId, false)

                    if (outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isOutputDone = true
                    }
                }
                outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "输出格式变化: ${codec.outputFormat}")
                }
            }
        }

        // 将累积的字节数据转换为ShortArray
        val bytes = outputStream.toByteArray()
        val shortArray = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().get(shortArray)
        return shortArray
    }

    /**
     * 重采样到16kHz单声道，并转为float数组
     */
    private fun resampleTo16kHzMono(
        pcmData: ShortArray,
        originalSampleRate: Int,
        channelCount: Int
    ): FloatArray {
        if (originalSampleRate == TARGET_SAMPLE_RATE && channelCount == 1) {
            // 已经是目标格式，直接转换
            return FloatArray(pcmData.size) { pcmData[it] / 32768.0f }
        }

        // 先转为单声道（取平均）
        val monoData = if (channelCount == 2) {
            ShortArray(pcmData.size / 2) { i ->
                ((pcmData[i * 2].toInt() + pcmData[i * 2 + 1].toInt()) / 2).toShort()
            }
        } else {
            pcmData
        }

        // 简单线性重采样
        if (originalSampleRate == TARGET_SAMPLE_RATE) {
            return FloatArray(monoData.size) { monoData[it] / 32768.0f }
        }

        val ratio = TARGET_SAMPLE_RATE.toDouble() / originalSampleRate.toDouble()
        val newSize = (monoData.size * ratio).toInt()
        val result = FloatArray(newSize)

        for (i in 0 until newSize) {
            val srcIndex = i / ratio
            val index0 = srcIndex.toInt()
            val index1 = minOf(index0 + 1, monoData.size - 1)
            val fraction = srcIndex - index0

            val sample = monoData[index0] * (1 - fraction) + monoData[index1] * fraction
            result[i] = (sample / 32768.0f).toFloat()
        }

        Log.d(TAG, "重采样: ${originalSampleRate}Hz ${channelCount}ch -> ${TARGET_SAMPLE_RATE}Hz 1ch, 样本数: ${monoData.size} -> ${newSize}")
        return result
    }
}
