package site.webbing.audiorec

import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 语音转文字异常，[message] 可直接展示给用户。
 */
class TranscriptionException(message: String) : Exception(message)

/**
 * OpenAI 兼容语音转文字客户端（默认对接硅基流动免费的 FunAudioLLM/SenseVoiceSmall）。
 *
 * 使用标准 multipart/form-data 上传音频文件，零额外第三方依赖，
 * 支持 64KB 流式写入以避免长录音文件 OOM，并对瞬时网络异常提供指数退避重试。
 */
object AudioTranscriber {
    private const val TAG = "AudioTranscriber"
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_BASE_MS = 2000L
    private const val BUFFER_SIZE = 64 * 1024

    fun transcribe(file: File, config: ImaConfig): String {
        val endpoint = config.asrEndpoint.trim()
        val apiKey = config.asrApiKey.trim()
        val model = config.asrModel.trim()

        if (endpoint.isBlank() || apiKey.isBlank() || model.isBlank()) {
            throw TranscriptionException("转写配置不完整，请在设置中填写转写接口地址、API Key 和模型名")
        }
        if (!file.exists() || file.length() <= 0L) {
            throw TranscriptionException("录音文件不存在或为空")
        }

        val url = URL(endpoint)
        var lastError: Exception? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            val boundary = "----ImaRecBoundary${UUID.randomUUID().toString().replace("-", "")}"
            val headerPart = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                append(model).append("\r\n")
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(file.name).append("\"\r\n")
                append("Content-Type: audio/mp4\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            val footerPart = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            val totalLength = headerPart.size.toLong() + file.length() + footerPart.size.toLong()

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 300_000
                doOutput = true
                useCaches = false
                setFixedLengthStreamingMode(totalLength)
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
            }

            try {
                Log.d(
                    TAG,
                    "transcribe start (attempt=${attempt + 1}): file=${file.name} size=${file.length()} model=$model endpoint=$endpoint",
                )
                BufferedOutputStream(conn.outputStream, BUFFER_SIZE).use { out ->
                    out.write(headerPart)
                    file.inputStream().use { input ->
                        input.copyTo(out, BUFFER_SIZE)
                    }
                    out.write(footerPart)
                    out.flush()
                }

                val code = conn.responseCode
                val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()

                if (code !in 200..299) {
                    val detail = extractErrorMessage(responseText).ifBlank { "HTTP $code" }
                    Log.e(TAG, "transcribe HTTP $code: $responseText")
                    // 4xx 客户端/鉴权/文件超限错误不重试，直接提示用户
                    if (code in 400..499 && code != 429 && code != 408) {
                        throw TranscriptionException("语音转写失败（HTTP $code）：$detail")
                    }
                    throw RuntimeException("HTTP $code：$detail")
                }

                val transcript = extractTranscriptText(responseText).trim()
                Log.d(TAG, "transcribe success: len=${transcript.length}")
                return transcript
            } catch (e: TranscriptionException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "transcribe attempt ${attempt + 1} failed", e)
                if (attempt < MAX_ATTEMPTS - 1) {
                    val backoffMs = RETRY_BASE_MS shl attempt
                    Thread.sleep(backoffMs)
                }
            } finally {
                conn.disconnect()
            }
        }

        throw TranscriptionException(
            "语音转写失败（重试 $MAX_ATTEMPTS 次仍失败）：${lastError?.localizedMessage ?: "网络异常"}",
        )
    }

    private fun extractTranscriptText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val obj = JSONObject(trimmed)
            obj.optString("text", "")
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun extractErrorMessage(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val obj = JSONObject(trimmed)
            val errObj = obj.optJSONObject("error")
            when {
                errObj != null -> errObj.optString("message", trimmed)
                obj.has("message") -> obj.optString("message", trimmed)
                obj.has("msg") -> obj.optString("msg", trimmed)
                else -> trimmed.take(200)
            }
        } catch (_: Exception) {
            trimmed.take(200)
        }
    }
}
