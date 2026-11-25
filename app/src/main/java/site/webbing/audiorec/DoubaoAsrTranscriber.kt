package site.webbing.audiorec

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 火山引擎（豆包）流式语音识别 2.0 WebSocket 客户端（SAUC v3 `bigmodel_nostream` 流式输入模式）。
 *
 * 工作流：
 * 1. 通过旧版 `X-Api-App-Key`（APP ID）+ `X-Api-Access-Key`（Access Token）+ `X-Api-Resource-Id` 建连 WSS；
 * 2. 不走对象存储：用 [PcmAudioDecoder] 本地将 `.m4a` 流式解码为 16kHz 16-bit 单声道 PCM；
 * 3. `language` 留空以自动支持方言，开启 `enable_speaker_info`、`show_utterances` 与 `enable_ddc`；
 * 4. 接收最终结果后按说话人合并连续分句并输出清晰的 Markdown 段落。
 */
object DoubaoAsrTranscriber {
    private const val TAG = "DoubaoAsrTranscriber"
    private const val MAX_ATTEMPTS = 2
    private const val RETRY_BASE_MS = 2_000L
    private const val MAX_QUEUE_BYTES = 262_144L
    private const val FINAL_WAIT_SECONDS = 180L
    private const val OPEN_TIMEOUT_SECONDS = 25L

    private const val VERSION_AND_HEADER_SIZE = 0x11
    private const val MSG_FULL_CLIENT_REQUEST = 0x01
    private const val MSG_AUDIO_ONLY_REQUEST = 0x02
    private const val MSG_SERVER_FULL_RESPONSE = 0x09
    private const val MSG_SERVER_ERROR_RESPONSE = 0x0F

    private const val FLAG_POS_SEQUENCE = 0x01
    private const val FLAG_NEG_FLAG = 0x02
    private const val FLAG_NEG_WITH_SEQUENCE = 0x03

    private const val SER_NONE = 0x00
    private const val SER_JSON = 0x01
    private const val COMP_NONE = 0x00
    private const val COMP_GZIP = 0x01

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    fun transcribe(file: File, config: ImaConfig): String {
        val wsUrl = config.doubaoWsUrl.trim()
        val resourceId = config.doubaoResourceId.trim()
        val appId = config.doubaoAppId.trim()
        val accessToken = config.doubaoAccessToken.trim()

        if (wsUrl.isBlank() || resourceId.isBlank() || appId.isBlank() || accessToken.isBlank()) {
            throw TranscriptionException("豆包语音识别配置不完整，请在设置中填写 APP ID 和 Access Token")
        }
        if (!file.exists() || file.length() <= 0L) {
            throw TranscriptionException("录音文件不存在或为空")
        }

        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return transcribeOnce(
                    file = file,
                    wsUrl = wsUrl,
                    resourceId = resourceId,
                    appId = appId,
                    accessToken = accessToken,
                )
            } catch (e: TranscriptionException) {
                // 鉴权/参数错误或明确协议错误直接抛出，不盲目重试
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "transcribeOnce attempt ${attempt + 1} failed", e)
                if (attempt < MAX_ATTEMPTS - 1) {
                    Thread.sleep(RETRY_BASE_MS shl attempt)
                }
            }
        }

        throw TranscriptionException(
            "豆包语音识别失败（重试 $MAX_ATTEMPTS 次仍失败）：${lastError?.localizedMessage ?: "网络异常"}",
        )
    }

    private fun transcribeOnce(
        file: File,
        wsUrl: String,
        resourceId: String,
        appId: String,
        accessToken: String,
    ): String {
        val connectId = UUID.randomUUID().toString()
        val request = Request.Builder()
            .url(wsUrl)
            .header("X-Api-App-Key", appId)
            .header("X-Api-Access-Key", accessToken)
            .header("X-Api-Resource-Id", resourceId)
            .header("X-Api-Connect-Id", connectId)
            .build()

        val openLatch = CountDownLatch(1)
        val completionLatch = CountDownLatch(1)
        val sendFinished = AtomicBoolean(false)
        val seenLast = AtomicBoolean(false)
        val errorRef = AtomicReference<Throwable?>(null)
        val finalJsonRef = AtomicReference<JSONObject?>(null)
        val lastRawPayloadRef = AtomicReference<ByteArray?>(null)
        val logIdRef = AtomicReference("")

        fun failWith(t: Throwable) {
            errorRef.compareAndSet(null, t)
            openLatch.countDown()
            completionLatch.countDown()
        }

        val ws = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val logId = response.header("X-Tt-Logid").orEmpty()
                    logIdRef.set(logId)
                    Log.d(TAG, "WSS connected: connectId=$connectId logid=$logId url=$wsUrl")
                    openLatch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    try {
                        val frame = parseServerFrame(
                            bytes = bytes.toByteArray(),
                            decodeJson = sendFinished.get(),
                        )
                        when (frame.messageType) {
                            MSG_SERVER_ERROR_RESPONSE -> {
                                val detail = frame.errorMessage.ifBlank { "未知协议错误" }
                                val logSuffix = logIdRef.get().takeIf { it.isNotBlank() }
                                    ?.let { " (logid=$it)" }
                                    .orEmpty()
                                val msg = "豆包语音识别失败（${frame.errorCode}）：$detail$logSuffix"
                                val code = frame.errorCode
                                // 45xxxxxx 属于参数/鉴权/空音频/格式错误，直接作为 TranscriptionException 不重试
                                val ex: Throwable = if (code in 40_000_000..49_999_999) {
                                    TranscriptionException(msg)
                                } else {
                                    RuntimeException(msg)
                                }
                                failWith(ex)
                                webSocket.close(1000, "error")
                            }

                            MSG_SERVER_FULL_RESPONSE -> {
                                if (frame.rawPayload != null) {
                                    lastRawPayloadRef.set(frame.rawPayload)
                                }
                                var json = frame.json
                                if (json == null && frame.isLast && frame.rawPayload != null) {
                                    json = decodeJsonPayload(
                                        serialization = frame.serialization,
                                        compression = frame.compression,
                                        payload = frame.rawPayload,
                                    )
                                }
                                if (json != null) {
                                    finalJsonRef.set(json)
                                }
                                if (frame.isLast) {
                                    seenLast.set(true)
                                    completionLatch.countDown()
                                    webSocket.close(1000, "done")
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "onMessage parse error", e)
                        failWith(e)
                        webSocket.close(1002, "parse error")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!seenLast.get() && errorRef.get() == null) {
                        failWith(RuntimeException("连接在收到最终结果前关闭（code=$code, reason=$reason）"))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val httpCode = response?.code
                    val logId = response?.header("X-Tt-Logid").orEmpty()
                    Log.e(TAG, "WSS failure: http=$httpCode logid=$logId", t)
                    if (httpCode != null && httpCode in 400..499) {
                        val bodyMsg = runCatching { response.body?.string() }.getOrNull().orEmpty()
                        val detail = bodyMsg.ifBlank { t.localizedMessage ?: "HTTP $httpCode" }
                        failWith(TranscriptionException("豆包语音鉴权或请求被拒（HTTP $httpCode）：$detail"))
                    } else {
                        failWith(t)
                    }
                }
            },
        )

        try {
            if (!openLatch.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw RuntimeException("连接豆包语音识别服务超时")
            }
            errorRef.get()?.let { throw it }

            var seq = 1
            val fullReq = buildFullClientRequest(seq)
            if (!ws.send(fullReq.toByteString())) {
                throw RuntimeException("发送初始化配置帧失败")
            }
            seq++

            var packetCount = 0
            PcmAudioDecoder.decodeToPcm16kMono(file) { pcmChunk, isLast ->
                errorRef.get()?.let { throw it }

                // 队列背压保护：避免长录音解码速度快于网络发送导致 OOM
                while (ws.queueSize() > MAX_QUEUE_BYTES) {
                    errorRef.get()?.let { throw it }
                    Thread.sleep(20)
                }

                val frame = buildAudioOnlyRequest(
                    seq = seq,
                    audioChunk = pcmChunk,
                    isLast = isLast,
                )
                if (!ws.send(frame.toByteString())) {
                    throw errorRef.get() ?: RuntimeException("发送音频数据包失败（seq=$seq）")
                }

                packetCount++
                if (!isLast) {
                    seq++
                    // 每 10 包做轻微节流，避免瞬间突发推流触发限流
                    if (packetCount % 10 == 0) {
                        Thread.sleep(10)
                    }
                }
            }
            sendFinished.set(true)
            Log.d(TAG, "All PCM packets sent: count=$packetCount connectId=$connectId")

            if (!completionLatch.await(FINAL_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw RuntimeException("等待豆包语音识别最终结果超时")
            }
            errorRef.get()?.let { throw it }

            val resultJson = finalJsonRef.get()
                ?: lastRawPayloadRef.get()?.let { raw ->
                    runCatching { decodeJsonPayload(SER_JSON, COMP_GZIP, raw) }.getOrNull()
                }
                ?: return ""

            return formatDoubaoResult(resultJson)
        } finally {
            runCatching { ws.close(1000, "finished") }
        }
    }

    private fun buildHeader(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
    ): ByteArray = byteArrayOf(
        VERSION_AND_HEADER_SIZE.toByte(),
        (((messageType and 0x0F) shl 4) or (flags and 0x0F)).toByte(),
        (((serialization and 0x0F) shl 4) or (compression and 0x0F)).toByte(),
        0x00,
    )

    private fun buildFullClientRequest(seq: Int): ByteArray {
        val payloadObj = JSONObject().apply {
            put("user", JSONObject().apply {
                put("uid", "imarec-android")
            })
            // 不传 audio.language（留空），以启用模型默认的中英及多方言识别
            put("audio", JSONObject().apply {
                put("format", "pcm")
                put("codec", "raw")
                put("rate", PcmAudioDecoder.TARGET_SAMPLE_RATE)
                put("bits", 16)
                put("channel", 1)
            })
            put("request", JSONObject().apply {
                put("model_name", "bigmodel")
                put("enable_itn", true)
                put("enable_punc", true)
                put("enable_ddc", true)
                put("show_utterances", true)
                put("enable_speaker_info", true)
                put("result_type", "full")
            })
        }

        val compressed = gzipCompress(payloadObj.toString().toByteArray(Charsets.UTF_8))
        val header = buildHeader(
            messageType = MSG_FULL_CLIENT_REQUEST,
            flags = FLAG_POS_SEQUENCE,
            serialization = SER_JSON,
            compression = COMP_GZIP,
        )
        return ByteBuffer.allocate(4 + 4 + 4 + compressed.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .putInt(seq)
            .putInt(compressed.size)
            .put(compressed)
            .array()
    }

    private fun buildAudioOnlyRequest(
        seq: Int,
        audioChunk: ByteArray,
        isLast: Boolean,
    ): ByteArray {
        val flags = if (isLast) FLAG_NEG_WITH_SEQUENCE else FLAG_POS_SEQUENCE
        val actualSeq = if (isLast) -seq else seq
        val compressed = gzipCompress(audioChunk)
        val header = buildHeader(
            messageType = MSG_AUDIO_ONLY_REQUEST,
            flags = flags,
            serialization = SER_NONE,
            compression = COMP_GZIP,
        )
        return ByteBuffer.allocate(4 + 4 + 4 + compressed.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .putInt(actualSeq)
            .putInt(compressed.size)
            .put(compressed)
            .array()
    }

    private data class ServerFrame(
        val messageType: Int,
        val sequence: Int?,
        val isLast: Boolean,
        val serialization: Int,
        val compression: Int,
        val json: JSONObject? = null,
        val rawPayload: ByteArray? = null,
        val errorCode: Int = 0,
        val errorMessage: String = "",
    )

    private fun parseServerFrame(bytes: ByteArray, decodeJson: Boolean): ServerFrame {
        if (bytes.size < 4) {
            throw RuntimeException("服务端返回帧长度不足 4 字节")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val b0 = buf.get().toInt() and 0xFF
        val b1 = buf.get().toInt() and 0xFF
        val b2 = buf.get().toInt() and 0xFF
        buf.get() // reserved

        val headerSizeBytes = (b0 and 0x0F) * 4
        val messageType = (b1 ushr 4) and 0x0F
        val flags = b1 and 0x0F
        val serialization = (b2 ushr 4) and 0x0F
        val compression = b2 and 0x0F

        if (headerSizeBytes > 4 && bytes.size >= headerSizeBytes) {
            buf.position(headerSizeBytes)
        }

        var sequence: Int? = null
        if ((flags and 0x01) != 0 && buf.remaining() >= 4) {
            sequence = buf.int
        }
        val isLast = (flags and FLAG_NEG_FLAG) != 0 || (sequence != null && sequence < 0)

        return when (messageType) {
            MSG_SERVER_ERROR_RESPONSE -> {
                val errorCode = if (buf.remaining() >= 4) buf.int else -1
                val payloadSize = if (buf.remaining() >= 4) (buf.int.toLong() and 0xFFFFFFFFL).toInt() else 0
                val payload = if (payloadSize > 0 && buf.remaining() >= payloadSize) {
                    ByteArray(payloadSize).also { buf.get(it) }
                } else {
                    ByteArray(0)
                }
                val errMsg = extractServerErrorText(serialization, compression, payload)
                ServerFrame(
                    messageType = messageType,
                    sequence = sequence,
                    isLast = true,
                    serialization = serialization,
                    compression = compression,
                    errorCode = errorCode,
                    errorMessage = errMsg,
                )
            }

            MSG_SERVER_FULL_RESPONSE -> {
                val payloadSize = if (buf.remaining() >= 4) (buf.int.toLong() and 0xFFFFFFFFL).toInt() else 0
                val payload = if (payloadSize > 0 && buf.remaining() >= payloadSize) {
                    ByteArray(payloadSize).also { buf.get(it) }
                } else {
                    ByteArray(0)
                }
                val parsedJson = if ((decodeJson || isLast) && payload.isNotEmpty()) {
                    runCatching { decodeJsonPayload(serialization, compression, payload) }.getOrNull()
                } else {
                    null
                }
                ServerFrame(
                    messageType = messageType,
                    sequence = sequence,
                    isLast = isLast,
                    serialization = serialization,
                    compression = compression,
                    json = parsedJson,
                    rawPayload = payload.takeIf { it.isNotEmpty() },
                )
            }

            else -> ServerFrame(
                messageType = messageType,
                sequence = sequence,
                isLast = isLast,
                serialization = serialization,
                compression = compression,
            )
        }
    }

    private fun extractServerErrorText(
        serialization: Int,
        compression: Int,
        payload: ByteArray,
    ): String {
        if (payload.isEmpty()) return ""
        val decoded = if (compression == COMP_GZIP) {
            runCatching { gzipDecompress(payload) }.getOrElse { payload }
        } else {
            payload
        }
        val text = decoded.toString(Charsets.UTF_8).trim()
        if (serialization == SER_JSON) {
            val obj = runCatching { JSONObject(text) }.getOrNull()
            if (obj != null) {
                return obj.optString("message").ifBlank {
                    obj.optString("error").ifBlank { text }
                }
            }
        }
        return text
    }

    private fun decodeJsonPayload(
        serialization: Int,
        compression: Int,
        payload: ByteArray,
    ): JSONObject? {
        if (payload.isEmpty()) return null
        val decoded = if (compression == COMP_GZIP) {
            gzipDecompress(payload)
        } else {
            payload
        }
        if (serialization != SER_JSON && serialization != SER_NONE) return null
        val text = decoded.toString(Charsets.UTF_8).trim()
        if (!text.startsWith("{")) return null
        return JSONObject(text)
    }

    private data class UtteranceItem(
        val speakerRaw: String,
        val startMs: Long,
        val text: String,
    )

    private data class SpeakerBlock(
        val speakerLabel: String,
        val startMs: Long,
        val body: StringBuilder,
    )

    /**
     * 将豆包返回的 `result.utterances` 按说话人合并连续发言段落并排版为 Markdown；
     * 若无说话人标识或无分句数组，则平滑回退到完整文本。
     */
    internal fun formatDoubaoResult(root: JSONObject): String {
        val resultObj = root.optJSONObject("result") ?: return ""
        val fallbackText = resultObj.optString("text", "").trim()
        val utterances = resultObj.optJSONArray("utterances")
        if (utterances == null || utterances.length() == 0) {
            return fallbackText
        }

        val items = ArrayList<UtteranceItem>(utterances.length())
        for (i in 0 until utterances.length()) {
            val u = utterances.optJSONObject(i) ?: continue
            val text = u.optString("text", "").trim()
            if (text.isEmpty()) continue
            val additions = u.optJSONObject("additions")
            val speakerRaw = additions?.optString("speaker").orEmpty()
                .ifBlank { additions?.optString("speaker_id").orEmpty() }
                .ifBlank { u.optString("speaker", "") }
                .ifBlank { u.optString("speaker_id", "") }
                .trim()
            val startMs = u.optLong("start_time", 0L).coerceAtLeast(0L)
            items.add(UtteranceItem(speakerRaw = speakerRaw, startMs = startMs, text = text))
        }

        if (items.isEmpty()) return fallbackText

        val hasAnySpeaker = items.any { it.speakerRaw.isNotBlank() }
        if (!hasAnySpeaker) {
            return buildString {
                for (item in items) {
                    appendSegmentText(this, item.text)
                }
            }.trim().ifBlank { fallbackText }
        }

        // 按出现顺序映射规范化说话人名称（如 "1" -> "说话人 1"）
        val speakerNameMap = LinkedHashMap<String, String>()

        fun resolveSpeakerLabel(raw: String): String {
            if (raw.isBlank()) {
                return speakerNameMap.getOrPut("") { "说话人" }
            }
            return speakerNameMap.getOrPut(raw) {
                if (raw.contains("说话人")) raw else "说话人 $raw"
            }
        }

        val blocks = ArrayList<SpeakerBlock>()
        var currentSpeakerKey: String? = null
        var currentBlock: SpeakerBlock? = null

        for (item in items) {
            val key = item.speakerRaw
            if (currentBlock == null || currentSpeakerKey != key) {
                val block = SpeakerBlock(
                    speakerLabel = resolveSpeakerLabel(key),
                    startMs = item.startMs,
                    body = StringBuilder(item.text),
                )
                blocks.add(block)
                currentBlock = block
                currentSpeakerKey = key
            } else {
                appendSegmentText(currentBlock.body, item.text)
            }
        }

        return blocks.joinToString("\n\n") { block ->
            "**${block.speakerLabel}** `[${formatTimestamp(block.startMs)}]`\n${block.body.toString().trim()}"
        }
    }

    private fun appendSegmentText(sb: StringBuilder, next: String) {
        if (sb.isEmpty()) {
            sb.append(next)
            return
        }
        val prevLast = sb[sb.length - 1]
        val nextFirst = next[0]
        if (prevLast.isAsciiWordChar() && nextFirst.isAsciiWordChar()) {
            sb.append(' ')
        }
        sb.append(next)
    }

    private fun Char.isAsciiWordChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun formatTimestamp(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gzipDecompress(data: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { return it.readBytes() }
    }
}
