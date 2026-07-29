package com.example.bilitranscript

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * B站及多平台视频下载器
 * 流程：解析链接 → 获取视频信息 → 获取音频流 → 下载音频
 */
class BiliDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // 专门用于解析短链接的client（不自动跟随重定向）
    private val redirectClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 从各种视频链接中提取平台标识：
     *  - B站 → BV 号
     *  - 抖音/小红书/快手 → `dy_/xhs_/ks_` + 编码后的原始链接
     */
    fun extractBvid(url: String): String? {
        val trimmed = url.trim()

        // 检测并匹配多平台链接
        if (trimmed.contains("douyin.com") || trimmed.contains("v.douyin.com") || trimmed.contains("iesdouyin.com")) {
            return "dy_" + URLEncoder.encode(trimmed, "UTF-8")
        }
        if (trimmed.contains("xiaohongshu.com") || trimmed.contains("xhslink.com")) {
            return "xhs_" + URLEncoder.encode(trimmed, "UTF-8")
        }
        if (trimmed.contains("kuaishou.com") || trimmed.contains("v.kuaishou.com") || trimmed.contains("gifshow.com")) {
            return "ks_" + URLEncoder.encode(trimmed, "UTF-8")
        }

        // 1. 直接从文本中提取BV号（最可靠）
        val bvRegex = Regex("BV[1-9A-HJ-NP-Za-km-z]{10}")
        val bvMatch = bvRegex.find(trimmed)
        if (bvMatch != null) {
            return bvMatch.value
        }

        // 2. 从URL路径中提取BV号（如 /video/BVxxxxx）
        val pathBvRegex = Regex("/BV([1-9A-HJ-NP-Za-km-z]{10})")
        val pathBvMatch = pathBvRegex.find(trimmed)
        if (pathBvMatch != null) {
            return pathBvMatch.value.substring(1) // 去掉开头的/
        }

        // 3. 短链接 b23.tv/xxxxx
        if (trimmed.contains("b23.tv") || trimmed.contains("bili2233.cn")) {
            return resolveShortLink(trimmed)
        }

        // 4. AV号转BV号
        val avRegex = Regex("av(\\d+)")
        val avMatch = avRegex.find(trimmed)
        if (avMatch != null) {
            return av2bv(avMatch.groupValues[1].toLong())
        }

        return null
    }

    /**
     * 解析B站短链接获取BV号
     * 通过发送HEAD请求获取302重定向的Location头
     */
    private fun resolveShortLink(shortUrl: String): String? {
        // 从文本中提取短链接URL
        val shortLinkRegex = Regex("https?://(b23\\.tv|bili2233\\.cn)/[A-Za-z0-9]+")
        val match = shortLinkRegex.find(shortUrl) ?: return null
        val actualShortUrl = match.value

        return try {
            // 方法1：获取302重定向的Location头
            val headRequest = Request.Builder()
                .url(actualShortUrl)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .header("Accept", "*/*")
                .build()

            val headResponse = redirectClient.newCall(headRequest).execute()
            val location = headResponse.header("Location")
            headResponse.close()

            if (location != null) {
                val bvid = extractBvid(location)
                if (bvid != null) return bvid
            }

            // 方法2：如果HEAD失败，尝试GET请求（跟随重定向）
            val getRequest = Request.Builder()
                .url(actualShortUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            val getResponse = client.newCall(getRequest).execute()
            val finalUrl = getResponse.request.url.toString()
            getResponse.close()

            extractBvid(finalUrl)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * AV号转BV号算法
     */
    private fun av2bv(av: Long): String {
        val table = "fZodR9XQDSUm21yCkr6zBqiveYah8bt4xsWpHnJE7jL5VG3guMTKNPAwcF"
        val s = intArrayOf(11, 10, 3, 8, 4, 6)
        val xor = 177451812L
        val add = 8728348608L

        var x = (av xor xor) + add
        val r = StringBuilder("BV1  4 1 7  ")

        for (i in 0..5) {
            val idx = ((x / Math.pow(58.0, i.toDouble()).toLong()) % 58).toInt()
            r[s[i]] = table[idx]
        }

        return r.toString()
    }

    /**
     * 获取视频信息
     */
    fun getVideoInfo(bvid: String): VideoInfo {
        if (bvid.startsWith("dy_")) {
            val shareUrl = URLDecoder.decode(bvid.substring(3), "UTF-8")
            return VideoInfo(
                bvid = bvid,
                cid = 0L,
                title = fetchExternalTitle(shareUrl) ?: "抖音视频",
                duration = 0
            )
        }
        if (bvid.startsWith("xhs_")) {
            val shareUrl = URLDecoder.decode(bvid.substring(4), "UTF-8")
            return VideoInfo(
                bvid = bvid,
                cid = 0L,
                title = fetchExternalTitle(shareUrl) ?: "小红书视频",
                duration = 0
            )
        }
        if (bvid.startsWith("ks_")) {
            val shareUrl = URLDecoder.decode(bvid.substring(3), "UTF-8")
            return VideoInfo(
                bvid = bvid,
                cid = 0L,
                title = fetchExternalTitle(shareUrl) ?: "快手视频",
                duration = 0
            )
        }

        // 获取视频基本信息
        val infoUrl = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
        val infoRequest = Request.Builder()
            .url(infoUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        val infoResponse = client.newCall(infoRequest).execute()
        if (!infoResponse.isSuccessful) {
            throw Exception("获取视频信息失败: ${infoResponse.code}")
        }

        val infoBody = infoResponse.body?.string() ?: throw Exception("响应为空")
        val infoData = json.decodeFromString<BiliResponse<VideoDetail>>(infoBody)

        if (infoData.code != 0) {
            throw Exception("B站API错误: ${infoData.message}")
        }

        val detail = infoData.data ?: throw Exception("视频信息为空")

        return VideoInfo(
            bvid = bvid,
            cid = detail.cid,
            title = detail.title,
            duration = detail.duration
        )
    }

    /**
     * 字幕优先：尝试抓取 B站 官方/AI 字幕。
     * 有字幕则直接返回文本+时间轴（100% 准确、秒出，无需识别）。
     * 没有字幕 / 需要登录但未提供 Cookie 时返回 null，由调用方降级到语音识别。
     *
     * @param sessdata 可选 B站 SESSDATA Cookie（很多视频的字幕需要登录才能取）
     */
    fun fetchSubtitle(videoInfo: VideoInfo, sessdata: String = ""): SubtitleData? {
        return try {
            val url = "https://api.bilibili.com/x/player/v2?bvid=${videoInfo.bvid}&cid=${videoInfo.cid}"
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .header("Referer", "https://www.bilibili.com/video/${videoInfo.bvid}")
            if (sessdata.isNotBlank()) builder.header("Cookie", "SESSDATA=$sessdata")

            val resp = client.newCall(builder.build()).execute()
            val body = resp.body?.string() ?: return null
            if (!resp.isSuccessful) return null

            val parsed = json.decodeFromString<BiliResponse<PlayerV2Data>>(body)
            val subtitles = parsed.data?.subtitle?.subtitles.orEmpty()
            if (subtitles.isEmpty()) return null

            // 优先中文字幕，否则取第一个
            val chosen = subtitles.firstOrNull { it.lan.lowercase() in CHINESE_LANS }
                ?: subtitles.first()

            var subUrl = chosen.subtitleUrl
            if (subUrl.isBlank()) return null
            if (subUrl.startsWith("//")) subUrl = "https:$subUrl"

            val subResp = client.newCall(
                Request.Builder()
                    .url(subUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    .build()
            ).execute()
            val subBody = subResp.body?.string() ?: return null
            if (!subResp.isSuccessful) return null

            val parsedSub = json.decodeFromString<SubtitleBody>(subBody)
            val lines = parsedSub.body.filter { it.content.isNotBlank() }
            if (lines.isEmpty()) return null

            val segments = lines.map {
                TranscriptSegment(
                    startMs = (it.from * 1000).toLong(),
                    endMs = (it.to * 1000).toLong(),
                    text = it.content.trim()
                )
            }
            SubtitleData(
                text = segments.joinToString("\n") { it.text },
                segments = segments,
                langDoc = chosen.lanDoc.ifBlank { chosen.lan }
            )
        } catch (e: Exception) {
            android.util.Log.w("BiliDownloader", "字幕抓取失败（降级到语音识别）: ${e.message}")
            null
        }
    }

    /**
     * 下载音频到指定文件
     * @param videoInfo 视频信息
     * @param outputFile 输出文件路径（建议使用应用缓存目录）
     * @param lowBitrate 是否优先下载最低码率音频流
     * @param onProgress 进度回调 (已下载字节, 总字节)
     * @return 下载成功返回true
     */
    fun downloadAudio(
        videoInfo: VideoInfo,
        outputFile: File,
        lowBitrate: Boolean = true,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean {
        if (videoInfo.bvid.startsWith("dy_")) {
            return try {
                val shareUrl = URLDecoder.decode(videoInfo.bvid.substring(3), "UTF-8")
                val streamUrl = getDouyinStreamUrl(shareUrl)
                downloadUrlToFile(streamUrl, outputFile, onProgress = onProgress)
            } catch (e: Exception) {
                android.util.Log.e("BiliDownloader", "抖音视频下载失败: ${e.message}", e)
                false
            }
        }
        if (videoInfo.bvid.startsWith("xhs_")) {
            return try {
                val shareUrl = URLDecoder.decode(videoInfo.bvid.substring(4), "UTF-8")
                val streamUrl = getXiaohongshuStreamUrl(shareUrl)
                downloadUrlToFile(streamUrl, outputFile, onProgress = onProgress)
            } catch (e: Exception) {
                android.util.Log.e("BiliDownloader", "小红书视频下载失败: ${e.message}", e)
                false
            }
        }
        if (videoInfo.bvid.startsWith("ks_")) {
            return try {
                val shareUrl = URLDecoder.decode(videoInfo.bvid.substring(3), "UTF-8")
                val streamUrl = getKuaishouStreamUrl(shareUrl)
                downloadUrlToFile(streamUrl, outputFile, onProgress = onProgress)
            } catch (e: Exception) {
                android.util.Log.e("BiliDownloader", "快手视频下载失败: ${e.message}", e)
                false
            }
        }

        // 获取音频流地址 (fnval=16 返回DASH格式)
        val playUrl = "https://api.bilibili.com/x/player/playurl?bvid=${videoInfo.bvid}&cid=${videoInfo.cid}&fnval=16"
        val playRequest = Request.Builder()
            .url(playUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        val playResponse = client.newCall(playRequest).execute()
        val playBody = playResponse.body?.string() ?: return false
        
        // 调试：打印API返回
        android.util.Log.d("BiliDownloader", "playurl response code=${playResponse.code}, body length=${playBody.length}")
        
        val playData = json.decodeFromString<BiliResponse<PlayData>>(playBody)

        if (playData.code != 0 || playData.data == null) {
            android.util.Log.e("BiliDownloader", "playurl API error: code=${playData.code}, msg=${playData.message}")
            return false
        }

        // 选择音频流：DASH 有多档音频（id 越小码率越低，如 30216≈64k / 30280≈192k）。
        // 识别只需 16kHz，默认选最低码率——下载更快、解码更快、精度无损。
        val dashAudios = playData.data.dash?.audio.orEmpty()
        val selectedDash = if (lowBitrate) dashAudios.minByOrNull { it.id } else dashAudios.maxByOrNull { it.id }
        val audioUrl = playData.data.durl?.firstOrNull()?.url ?: selectedDash?.baseUrl

        if (audioUrl == null) {
            android.util.Log.e("BiliDownloader", "No audio URL found. hasDash=${playData.data.dash != null}, hasDurl=${playData.data.durl != null}")
            return false
        }
        
        android.util.Log.d("BiliDownloader", "Audio URL: ${audioUrl.take(80)}")

        // 下载音频 - 使用完整浏览器请求头绕过CDN防盗链
        val chromeUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        
        val audioRequest = Request.Builder()
            .url(audioUrl)
            .header("User-Agent", chromeUa)
            .header("Referer", "https://www.bilibili.com/video/${videoInfo.bvid}")
            .header("Origin", "https://www.bilibili.com")
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        val audioResponse = client.newCall(audioRequest).execute()
        val contentLen = audioResponse.body?.contentLength() ?: -1
        android.util.Log.d("BiliDownloader", "Audio download response: ${audioResponse.code}, contentLength=$contentLen")
        
        if (!audioResponse.isSuccessful) {
            android.util.Log.e("BiliDownloader", "Audio download failed: ${audioResponse.code}")
            // 尝试备用URL
            val backupUrl = selectedDash?.backupUrl?.firstOrNull()
                ?: playData.data.durl?.firstOrNull()?.backupUrl?.firstOrNull()
            if (backupUrl != null) {
                android.util.Log.d("BiliDownloader", "Trying backup URL...")
                val backupRequest = Request.Builder()
                    .url(backupUrl)
                    .header("User-Agent", chromeUa)
                    .header("Referer", "https://www.bilibili.com/video/${videoInfo.bvid}")
                    .header("Origin", "https://www.bilibili.com")
                    .header("Accept", "*/*")
                    .build()
                val backupResponse = client.newCall(backupRequest).execute()
                android.util.Log.d("BiliDownloader", "Backup URL response: ${backupResponse.code}")
                if (backupResponse.isSuccessful || backupResponse.code == 206) {
                    val blen = backupResponse.body?.contentLength() ?: -1
                    pumpToFile(backupResponse, outputFile, append = false, totalForProgress = blen, alreadyHave = 0, onProgress = onProgress)
                    resumeUntilComplete(backupUrl, chromeUa, videoInfo.bvid, outputFile, blen, onProgress)
                    val success = isComplete(outputFile, blen)
                    android.util.Log.d("BiliDownloader", "Backup download result: $success, size=${outputFile.length()}, expected=$blen")
                    if (!success) outputFile.delete()
                    return success
                }
            }
            return false
        }

        // 保存到输出文件（带进度 + 断流续传 + 完整性校验）。
        // CDN 断流时 read 返回 -1 会被误认为正常结束；不校验的话残缺的 m4a
        // 只能解出前几秒（"长视频只识别出几个字"的经典元凶），必须拦截。
        pumpToFile(audioResponse, outputFile, append = false, totalForProgress = contentLen, alreadyHave = 0, onProgress = onProgress)
        resumeUntilComplete(audioUrl, chromeUa, videoInfo.bvid, outputFile, contentLen, onProgress)

        val success = isComplete(outputFile, contentLen)
        android.util.Log.d("BiliDownloader", "Download result: $success, size=${outputFile.length()}, expected=$contentLen")
        if (!success) outputFile.delete()
        return success
    }

    /** 把响应体写入文件（append=true 为续写），带进度回调。 */
    private fun pumpToFile(
        resp: okhttp3.Response,
        outputFile: File,
        append: Boolean,
        totalForProgress: Long,
        alreadyHave: Long,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        resp.body?.byteStream()?.use { input ->
            java.io.FileOutputStream(outputFile, append).use { output ->
                val buffer = ByteArray(8192)
                var written = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    written += read
                    if (totalForProgress > 0) onProgress?.invoke(alreadyHave + written, totalForProgress)
                }
            }
        }
    }

    /** 文件不完整时按 Range 续传，最多 3 轮。 */
    private fun resumeUntilComplete(
        audioUrl: String,
        chromeUa: String,
        bvid: String,
        outputFile: File,
        contentLen: Long,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        var rounds = 0
        while (contentLen > 0 && outputFile.length() < contentLen && rounds < 3) {
            rounds++
            android.util.Log.w("BiliDownloader", "音频不完整(${outputFile.length()}/$contentLen)，第 $rounds 轮 Range 续传...")
            try {
                val resumeReq = Request.Builder()
                    .url(audioUrl)
                    .header("User-Agent", chromeUa)
                    .header("Referer", "https://www.bilibili.com/video/$bvid")
                    .header("Origin", "https://www.bilibili.com")
                    .header("Accept", "*/*")
                    .header("Range", "bytes=${outputFile.length()}-")
                    .build()
                val rr = client.newCall(resumeReq).execute()
                if (!rr.isSuccessful && rr.code != 206) {
                    android.util.Log.w("BiliDownloader", "续传请求被拒: HTTP ${rr.code}")
                    return
                }
                pumpToFile(rr, outputFile, append = true, totalForProgress = contentLen, alreadyHave = outputFile.length(), onProgress = onProgress)
            } catch (e: Exception) {
                android.util.Log.w("BiliDownloader", "续传异常(第${rounds}轮): ${e.message}")
            }
        }
        if (contentLen > 0 && outputFile.length() < contentLen) {
            android.util.Log.e("BiliDownloader", "续传后仍不完整: ${outputFile.length()}/$contentLen")
        }
    }

    /** 完整性判定：文件存在、非空、且达到声明长度（无声明长度时按非空算）。 */
    private fun isComplete(outputFile: File, contentLen: Long): Boolean =
        outputFile.exists() && outputFile.length() > 0 && (contentLen <= 0 || outputFile.length() >= contentLen)

    private fun getDouyinStreamUrl(shareUrl: String): String {
        val realUrl = getFinalRedirectUrl(shareUrl) ?: shareUrl
        val req = Request.Builder()
            .url(realUrl)
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: throw Exception("抖音页面内容为空")
            // RENDER_DATA 场景：整页 JSON 是 URL 编码的，先解码一份一起搜
            val decoded = try { URLDecoder.decode(html, "UTF-8") } catch (e: Exception) { "" }
            val haystacks = listOf(html, decoded)
            val patterns = listOf(
                Regex("\"playAddr\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"play_addr\"\\s*:\\s*\\{\\s*\"url_list\"\\s*:\\s*\\[\\s*\"([^\"]+)\""),
                Regex("\"download_addr\"[^}]*\"url_list\"\\s*:\\s*\\[\\s*\"([^\"]+)\""),
                Regex("property=\"og:video\"\\s*content=\"([^\"]+)\""),
                Regex("https?://[a-zA-Z0-9.-]+/aweme/v1/play/[^\"\\s]+")
            )
            for (hay in haystacks) {
                for (p in patterns) {
                    val m = p.find(hay)
                    if (m != null) {
                        return m.groupValues[1].ifBlank { m.value }
                            .replace("\\u002F", "/").replace("\\u0026", "&")
                    }
                }
            }
            throw Exception("抖音链接解析失败：平台风控升级（页面需验证），请稍后重试，或改用 B站 链接")
        }
    }

    private fun getXiaohongshuStreamUrl(shareUrl: String): String {
        val realUrl = getFinalRedirectUrl(shareUrl) ?: shareUrl
        val req = Request.Builder()
            .url(realUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: throw Exception("小红书页面内容为空")
            val patterns = listOf(
                Regex("\"masterUrl\"\\s*:\\s*\"([^\"]+)\""),
                Regex("property=\"og:video\"\\s*content=\"([^\"]+)\""),
                Regex("\"url\"\\s*:\\s*\"(https?://[^\"]*?\\.mp4[^\"]*)\""),
                Regex("\"h264\"[^}]*\"url\"\\s*:\\s*\"([^\"]+)\"")
            )
            for (p in patterns) {
                val m = p.find(html)
                if (m != null) {
                    return m.groupValues[1].replace("\\u002F", "/").replace("\\u0026", "&")
                }
            }
            if (html.contains("imageList") || html.contains("\"images\"")) {
                throw Exception("该小红书笔记为图片贴，非视频贴，没有音轨可提取")
            }
            throw Exception("小红书链接解析失败：页面需登录或触发风控，请稍后重试，或改用 B站 链接")
        }
    }

    private fun getKuaishouStreamUrl(shareUrl: String): String {
        val realUrl = getFinalRedirectUrl(shareUrl) ?: shareUrl
        val req = Request.Builder()
            .url(realUrl)
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: throw Exception("快手页面内容为空")
            val decoded = try { URLDecoder.decode(html, "UTF-8") } catch (e: Exception) { "" }
            val haystacks = listOf(html, decoded)
            val patterns = listOf(
                Regex("\"playUrl\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"photoUrl\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"mainMvUrls\"\\s*:\\s*\\[\\s*\\{[^}]*\"url\"\\s*:\\s*\"([^\"]+)\""),
                Regex("property=\"og:video\"\\s*content=\"([^\"]+)\"")
            )
            for (hay in haystacks) {
                for (p in patterns) {
                    val m = p.find(hay)
                    if (m != null) {
                        return m.groupValues[1].replace("\\u002F", "/").replace("\\u0026", "&")
                    }
                }
            }
            throw Exception("快手链接解析失败：页面需登录或触发风控，请稍后重试，或改用 B站 链接")
        }
    }

    /**
     * 抓取外部平台页面标题（og:title / desc / caption / <title> 多备选）。
     * 被风控/登录墙拦截时返回 null（调用方回落通用名）。
     */
    private fun fetchExternalTitle(shareUrl: String): String? {
        return try {
            val realUrl = getFinalRedirectUrl(shareUrl) ?: shareUrl
            val req = Request.Builder()
                .url(realUrl)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
                .build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: return null
                val patterns = listOf(
                    Regex("property=\"og:title\"\\s*content=\"([^\"]+)\""),
                    Regex("\"desc\"\\s*:\\s*\"([^\"]{1,80})\""),
                    Regex("\"caption\"\\s*:\\s*\"([^\"]{1,80})\""),
                    Regex("<title>([^<]{1,80})</title>")
                )
                patterns.firstNotNullOfOrNull { p -> p.find(html)?.groupValues?.get(1) }
                    ?.replace("\\u002F", "/")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && !it.contains("验证") && !it.contains("登录") }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getFinalRedirectUrl(shortUrl: String): String? {
        val urlPattern = Regex("https?://[a-zA-Z0-9./_?=&%-]+")
        val match = urlPattern.find(shortUrl) ?: return null
        val actualUrl = match.value
        return try {
            val req = Request.Builder()
                .url(actualUrl)
                .head()
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
                .build()
            redirectClient.newCall(req).execute().use { resp ->
                val loc = resp.header("Location")
                if (!loc.isNullOrBlank()) {
                    if (loc.startsWith("http")) loc else "https:$loc"
                } else {
                    resp.request.url.toString()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadUrlToFile(url: String, outputFile: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        val chromeUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", chromeUa)
            .header("Accept", "*/*")
            .build()
        val response = client.newCall(req).execute()
        if (!response.isSuccessful) return false
        val contentLen = response.body?.contentLength() ?: -1
        pumpToFile(response, outputFile, append = false, totalForProgress = contentLen, alreadyHave = 0, onProgress = onProgress)
        val ok = isComplete(outputFile, contentLen)
        if (!ok) {
            android.util.Log.e("BiliDownloader", "站外音频下载不完整: ${outputFile.length()}/$contentLen")
            outputFile.delete()
        }
        return ok
    }
}

// ============ 数据类 ============

data class VideoInfo(
    val bvid: String,
    val cid: Long,
    val title: String,
    val duration: Int
)

@Serializable
data class BiliResponse<T>(
    val code: Int,
    val message: String,
    val data: T? = null
)

@Serializable
data class VideoDetail(
    val bvid: String,
    val cid: Long,
    val title: String,
    val duration: Int
)

@Serializable
data class PlayData(
    val dash: DashData? = null,
    val durl: List<DurlData>? = null
)

@Serializable
data class DashData(
    val audio: List<DashAudio>? = null
)

@Serializable
data class DashAudio(
    val baseUrl: String,
    @SerialName("backup_url")
    val backupUrl: List<String>? = null,
    val id: Int
)

@Serializable
data class DurlData(
    val url: String,
    @SerialName("backup_url")
    val backupUrl: List<String>? = null,
    val size: Long
)

// ============ 字幕相关 ============

/** 中文字幕语言代码 */
private val CHINESE_LANS = setOf("zh-cn", "zh-hans", "zh", "chi", "ai-zh")

/** 字幕抓取结果 */
data class SubtitleData(
    val text: String,
    val segments: List<TranscriptSegment>,
    val langDoc: String
)

@Serializable
data class PlayerV2Data(
    val subtitle: SubtitleInfo? = null
)

@Serializable
data class SubtitleInfo(
    val subtitles: List<SubtitleItem> = emptyList()
)

@Serializable
data class SubtitleItem(
    val lan: String = "",
    @SerialName("lan_doc")
    val lanDoc: String = "",
    @SerialName("subtitle_url")
    val subtitleUrl: String = ""
)

@Serializable
data class SubtitleBody(
    val body: List<SubtitleLine> = emptyList()
)

@Serializable
data class SubtitleLine(
    val from: Double = 0.0,
    val to: Double = 0.0,
    val content: String = ""
)
