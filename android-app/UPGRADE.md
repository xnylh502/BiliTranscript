# 升级说明 v2（本次大改）

本次把 App 从「单页提取」重构为「**共享管线 + 三页大厂风 UI + 历史/设置 + 字幕优先 + 可插拔大模型/人声分离**」。
下面分两部分：**开箱即用的**（已写好，编译即生效）和**需要你放模型才生效的**。

---

## 一、开箱即用（无需任何模型文件，编译即生效）

| 能力 | 说明 |
|---|---|
| **字幕优先** | 有 B站 官方/AI 字幕时直接抓取，秒出、100% 准确，自动降级到语音识别 |
| **低码率音频** | 默认下载最低码率音频流，下载/解码更快，识别精度无损 |
| **历史记录** | 每次提取自动入库（JSON 文件存储，零额外依赖），可搜索/收藏/删除/秒开 |
| **设置页** | 引擎切换、语言、线程、字幕优先、自动复制、缓存清理、Cookie 等全部可调 |
| **分享入口** | 在 B站 点「分享 → 文案福特」直接开提；也可直接「打开」B站链接 |
| **剪贴板识别** | 打开 App 自动识别剪贴板里的 B站 链接并预填 |
| **SRT 导出** | 字幕来源带时间轴时，可导出 `.srt` 字幕文件 |
| **缓存自愈** | 提取无论成败都清缓存（修复旧漏洞），启动再清扫一次残留 |
| **共享管线** | 主界面与悬浮球走同一条 `TranscriptionPipeline`，不再两份重复代码 |

新增/改动的核心文件：

```
AppSettings.kt            设置数据 + SharedPreferences 仓库
HistoryRepository.kt      历史记录（JSON 文件）
TranscriptResult.kt       共享结果类型（含时间轴段）
AppGraph.kt               极简依赖容器（单例）
TranscriptionPipeline.kt  共享提取管线（字幕优先→下载→解码→分离→识别）
VocalSeparator.kt         人声分离架子（待接后端）
SrtExporter.kt            SRT 导出
SpeechRecognizer.kt       改为按设置驱动 + Whisper 路径
BiliDownloader.kt         + 字幕抓取 + 低码率选择
UiComponents/Home/History/Settings Screen + MainActivity   全新 UI
```

> 直接 `./gradlew assembleDebug` 即可，**不需要改任何依赖**。

---

## 二、开启 Whisper 大模型「高精度模式」（需放模型，约 +1GB）

> 适合：背景音乐/噪声/口音/多语种混说等「难音频」。干净人声不必开，SenseVoice 已经够好且快得多。

1. 到 sherpa-onnx 预训练模型处下载 Whisper（**推荐 `large-v3-turbo`，比 large-v3 快很多**）：
   - GitHub：`k2-fsa/sherpa-onnx` releases 里的 `sherpa-onnx-whisper-*` 系列
   - 或 ModelScope 搜 `sherpa-onnx whisper`
2. 解压后把这三个文件放进：
   ```
   app/src/main/assets/models/whisper/
   ├── *encoder*.onnx     (如 large-v3-turbo-encoder.int8.onnx)
   ├── *decoder*.onnx     (如 large-v3-turbo-decoder.int8.onnx)
   └── *tokens.txt        (如 large-v3-turbo-tokens.txt)
   ```
   文件名不用改，代码会自动按 `encoder/decoder/tokens.txt` 关键字识别。
3. 重新编译。打开 App → 设置 → 识别引擎 → 选「Whisper 精」。
   - 检测不到模型时该选项会提示，并自动回落 SenseVoice，不会崩。

⚠️ APK 会因此增大约 1GB；首次加载更慢；长视频识别耗时明显增加（你机器扛得住，但要有预期）。

---

## 三、开启人声分离（去背景音乐）—— 最硬的一块，需放模型 + 接后端

这是「有音乐也清晰」的根本解法。架子（`VocalSeparator.kt` + 管线调用点 + 设置开关）已就绪，
**缺的是分离模型 + 推理后端**，二选一：

**路线 A：用 sherpa-onnx 自带源分离（若你的版本支持）**
- 确认当前 `sherpa-onnx-1.13.0.aar` 是否提供离线源分离 API（UVR/Spleeter）。
- 放入对应模型到 `app/src/main/assets/models/separation/*.onnx`。
- 在 `VocalSeparator.separate()` 内调用该 API，把 `BACKEND_WIRED` 改为 `true`。

**路线 B：引入 onnxruntime 直接跑 MDX-Net**
- 下载一个 MDX-Net/UVR 的 ONNX（约 50–100MB）放到 `assets/models/separation/`。
- 加依赖 `com.microsoft.onnxruntime:onnxruntime-android`，在 `VocalSeparator.separate()` 用 `OrtSession` 推理。
- ⚠️ **native 冲突警告**：它和 sherpa 自带的 `libonnxruntime.so` 可能冲突，需在
  `build.gradle.kts` 的 `packaging { jniLibs { pickFirsts += "**/libonnxruntime.so" } }` 处理，
  集成前务必单机验证。
- 跑通后把 `BACKEND_WIRED` 改为 `true`。

模型/后端就绪前，分离开关在设置里会显示「未安装」，管线自动跳过，**当前行为安全不变**。

---

## 四、字幕优先需要登录的情况

部分视频的字幕需要 B站 登录才能取。设置页底部可填 **SESSDATA**（浏览器登录 B站 后从 Cookie 复制），
留空则只取公开字幕、其余自动降级到语音识别。

---

## 五、后续可继续做（本次未做）

- VAD（Silero）切句：跳过纯音乐段、给 SenseVoice 也生成时间轴（从而支持 SenseVoice 导出 SRT）
- LLM 文案润色（加标点/分段/摘要/翻译）：本地 Qwen(llama.cpp) 或云 API
- 说话人分离（diarization）
- 多平台（抖音/快手/YouTube/小红书）
