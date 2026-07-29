package com.example.bilitranscript

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 百度通用文字识别（OCR）客户端，用于「视频画面硬字幕」识别。
 *
 * - 接口：`/rest/2.0/ocr/v1/general_basic`（标准版，免费额度大方，字幕场景足够）
 * - access_token 30 天有效，本地缓存、提前 1 天刷新；key 变更自动失效
 * - 返回带文字位置（top/left/width/height），供调用方做「字幕区/水印」过滤
 *
 * 密钥由用户在设置页填写（百度智能云控制台 → 文字识别 → 创建应用）。
 */
class BaiduOcrClient(private val context: Context) {

    /** 一行识别结果（坐标相对所提交图片的像素）。 */
    data class OcrLine(
        val text: String,
        val top: Int,
        val left: Int,
        val width: Int,
        val height: Int
    ) {
        val centerX: Int get() = left + width / 2
    }

    class OcrException(message: String) : Exception(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpireAt: Long = 0L
    @Volatile private var tokenKeySig: String = ""

    /** 识别一张 JPEG 图片，返回文字行（含位置）。 */
    suspend fun recognize(jpegBytes: ByteArray, apiKey: String, secretKey: String): List<OcrLine> = withContext(Dispatchers.IO) {
        val token = getToken(apiKey, secretKey)
        try {
            return@withContext doRecognize(jpegBytes, token)
        } catch (e: OcrException) {
            // token 失效场景：强制刷新重试一次
            if (e.message?.contains("token", ignoreCase = true) == true) {
                Log.w(TAG, "access_token 失效，刷新后重试")
                val fresh = getToken(apiKey, secretKey, forceRefresh = true)
                return@withContext doRecognize(jpegBytes, fresh)
            }
            throw e
        }
    }

    private fun doRecognize(jpegBytes: ByteArray, token: String): List<OcrLine> {
        val b64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
        val body = FormBody.Builder()
            .add("image", URLEncoder.encode(b64, "UTF-8"))
            .build()
        val req = Request.Builder()
            .url("https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic?access_token=$token")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: throw OcrException("OCR 响应为空")
            val parsed = json.decodeFromString<OcrResponse>(text)
            parsed.errorMsg?.let { msg ->
                throw OcrException("OCR 接口错误(${parsed.errorCode}): $msg")
            }
            return parsed.wordsResult.orEmpty().map {
                OcrLine(
                    text = it.words.trim(),
                    top = it.location?.top ?: 0,
                    left = it.location?.left ?: 0,
                    width = it.location?.width ?: 0,
                    height = it.location?.height ?: 0
                )
            }.filter { it.text.isNotBlank() }
        }
    }

    private suspend fun getToken(apiKey: String, secretKey: String, forceRefresh: Boolean = false): String {
        val sig = "$apiKey|$secretKey"
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedToken != null && tokenKeySig == sig && now < tokenExpireAt) {
            return cachedToken!!
        }
        return tokenMutex.withLock {
            // 双重检查（等待锁期间可能已被其他协程刷新）
            if (!forceRefresh && cachedToken != null && tokenKeySig == sig && System.currentTimeMillis() < tokenExpireAt) {
                return@withLock cachedToken!!
            }
            val req = Request.Builder()
                .url("https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey")
                .post(FormBody.Builder().build())
                .build()
            val token = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: throw OcrException("token 响应为空")
                    val parsed = json.decodeFromString<TokenResponse>(text)
                    parsed.accessToken ?: throw OcrException(
                        "获取百度 access_token 失败: ${parsed.errorDescription ?: parsed.error ?: text.take(120)}（请检查 API Key / Secret Key 是否正确、应用是否已勾选「文字识别」能力）"
                    )
                }
            }
            cachedToken = token
            tokenKeySig = sig
            // 30 天有效，提前 1 天刷新
            tokenExpireAt = System.currentTimeMillis() + 29L * 24 * 3600 * 1000
            Log.i(TAG, "百度 OCR access_token 已获取并缓存")
            token
        }
    }

    // ---- 响应模型 ----

    @Serializable
    private data class TokenResponse(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String? = null,
        val error: String? = null,
        @kotlinx.serialization.SerialName("error_description") val errorDescription: String? = null
    )

    @Serializable
    private data class OcrResponse(
        @kotlinx.serialization.SerialName("words_result") val wordsResult: List<Word>? = null,
        @kotlinx.serialization.SerialName("error_code") val errorCode: Int? = null,
        @kotlinx.serialization.SerialName("error_msg") val errorMsg: String? = null
    )

    @Serializable
    private data class Word(
        val words: String = "",
        val location: Loc? = null
    )

    @Serializable
    private data class Loc(
        val top: Int = 0,
        val left: Int = 0,
        val width: Int = 0,
        val height: Int = 0
    )

    companion object {
        private const val TAG = "BaiduOcrClient"
    }
}
