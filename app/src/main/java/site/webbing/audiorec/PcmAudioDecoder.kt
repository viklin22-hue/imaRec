package site.webbing.audiorec

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 使用系统原生 [MediaExtractor] + [MediaCodec] 将本地 `.m4a` 等音频文件
 * 流式解码并重采样为 16kHz / 16-bit signed little-endian / 单声道 PCM，
 * 按 6400 字节（200ms）分块回调，避免长录音整段解码占用大量内存。
 */
object PcmAudioDecoder {
    const val TARGET_SAMPLE_RATE = 16_000
    const val CHUNK_BYTES = 6_400 // 16000 Hz * 2 bytes * 0.2s = 6400 bytes

    private const val TIMEOUT_US = 10_000L

    fun decodeToPcm16kMono(
        file: File,
        onChunk: (pcmBytes: ByteArray, isLast: Boolean) -> Unit,
    ) {
        if (!file.exists() || file.length() <= 0L) {
            throw TranscriptionException("录音文件不存在或为空")
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            var mime = ""

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val trackMime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (trackMime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    mime = trackMime
                    break
                }
            }

            if (audioTrackIndex < 0 || trackFormat == null || mime.isBlank()) {
                throw TranscriptionException("音频文件中未找到可解码的音轨")
            }

            extractor.selectTrack(audioTrackIndex)

            var currentSampleRate = trackFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var currentChannels = trackFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            var currentPcmEncoding = trackFormat.getIntOrDefault(
                MediaFormat.KEY_PCM_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT,
            )

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(trackFormat, null, null, 0)
                start()
            }

            val resampler = ContinuousLinearResampler()
            val bufferInfo = MediaCodec.BufferInfo()
            val chunkBuffer = ByteArrayOutputStream(CHUNK_BYTES * 2)
            var pendingChunk: ByteArray? = null
            var inputDone = false
            var outputDone = false

            fun flushReadyChunks() {
                val data = chunkBuffer.toByteArray()
                if (data.size < CHUNK_BYTES) return
                var offset = 0
                while (offset + CHUNK_BYTES <= data.size) {
                    val ready = data.copyOfRange(offset, offset + CHUNK_BYTES)
                    pendingChunk?.let { prev -> onChunk(prev, false) }
                    pendingChunk = ready
                    offset += CHUNK_BYTES
                }
                chunkBuffer.reset()
                if (offset < data.size) {
                    chunkBuffer.write(data, offset, data.size - offset)
                }
            }

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) {
                            inBuf.clear()
                            extractor.readSampleData(inBuf, 0)
                        } else {
                            -1
                        }
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        currentSampleRate = outFmt.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, currentSampleRate)
                        currentChannels = outFmt.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, currentChannels)
                            .coerceAtLeast(1)
                        currentPcmEncoding = outFmt.getIntOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            currentPcmEncoding,
                        )
                    }

                    outIdx >= 0 -> {
                        val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isCodecConfig && bufferInfo.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val monoSamples = extractMono16Samples(
                                    buf = outBuf,
                                    byteCount = bufferInfo.size,
                                    channels = currentChannels,
                                    pcmEncoding = currentPcmEncoding,
                                )
                                if (monoSamples.isNotEmpty()) {
                                    resampler.processTo16kLittleEndian(
                                        inSamples = monoSamples,
                                        inSampleRate = currentSampleRate,
                                        outStream = chunkBuffer,
                                    )
                                    flushReadyChunks()
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            // 尾包 look-ahead 冲刷：确保最后一包精确携带 isLast = true
            val tail = chunkBuffer.toByteArray()
            val prev = pendingChunk
            when {
                tail.isNotEmpty() -> {
                    if (prev != null) onChunk(prev, false)
                    onChunk(tail, true)
                }
                prev != null -> onChunk(prev, true)
                else -> onChunk(ByteArray(0), true)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun extractMono16Samples(
        buf: ByteBuffer,
        byteCount: Int,
        channels: Int,
        pcmEncoding: Int,
    ): ShortArray {
        val leBuf = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val totalFloats = byteCount / 4
                val frameCount = totalFloats / channels
                if (frameCount <= 0) return ShortArray(0)
                val out = ShortArray(frameCount)
                for (i in 0 until frameCount) {
                    var sum = 0f
                    for (ch in 0 until channels) {
                        sum += leBuf.getFloat()
                    }
                    val scaled = (sum / channels).coerceIn(-1f, 1f) * 32767f
                    out[i] = scaled.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                out
            }

            else -> {
                val totalShorts = byteCount / 2
                val frameCount = totalShorts / channels
                if (frameCount <= 0) return ShortArray(0)
                val out = ShortArray(frameCount)
                if (channels == 1) {
                    leBuf.asShortBuffer().get(out)
                } else {
                    for (i in 0 until frameCount) {
                        var sum = 0
                        for (ch in 0 until channels) {
                            sum += leBuf.getShort().toInt()
                        }
                        out[i] = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                out
            }
        }
    }

    private fun MediaFormat.getIntOrDefault(key: String, defaultValue: Int): Int =
        if (containsKey(key)) getInteger(key) else defaultValue

    /**
     * 跨输出缓冲的连续线性重采样器（任意采样率 -> 16000Hz little-endian 16-bit PCM）。
     * 通过保留上一缓冲末尾采样点 [carrySample] 与小数相位 [phase]，消除分帧拼接处的爆音。
     */
    private class ContinuousLinearResampler {
        private var carrySample: Short? = null
        private var phase: Double = 0.0
        private var lastRate: Int = 0

        fun processTo16kLittleEndian(
            inSamples: ShortArray,
            inSampleRate: Int,
            outStream: ByteArrayOutputStream,
        ) {
            if (inSamples.isEmpty()) return

            if (inSampleRate == TARGET_SAMPLE_RATE) {
                carrySample = inSamples[inSamples.lastIndex]
                phase = 0.0
                lastRate = inSampleRate
                for (sample in inSamples) {
                    writeLittleEndianShort(outStream, sample.toInt())
                }
                return
            }

            if (lastRate != inSampleRate) {
                if (lastRate == 0) phase = 0.0
                lastRate = inSampleRate
            }

            val hasCarry = carrySample != null
            val offset = if (hasCarry) 1 else 0
            val total = inSamples.size + offset
            val step = inSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()

            fun sampleAt(idx: Int): Double =
                if (hasCarry && idx == 0) {
                    carrySample!!.toDouble()
                } else {
                    inSamples[idx - offset].toDouble()
                }

            while (phase + 1.0 < total) {
                val i0 = phase.toInt()
                val frac = phase - i0
                val s0 = sampleAt(i0)
                val s1 = sampleAt(i0 + 1)
                val interp = (s0 + (s1 - s0) * frac)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                writeLittleEndianShort(outStream, interp)
                phase += step
            }

            carrySample = inSamples[inSamples.lastIndex]
            phase -= (total - 1)
            if (phase < 0.0) phase = 0.0
        }

        private fun writeLittleEndianShort(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }
    }
}
