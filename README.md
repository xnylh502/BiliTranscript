# 文案福特 · BiliTranscript

> 把 B站 视频里**说的话**，用**手机本地 AI** 转成文字 —— 全程离线、不传云端。

粘贴 B站 链接 → 下载音频 → 本地语音识别 → 一键复制 / 分享 / 导出。支持中 / 英 / 日 / 韩 / 粤，可切换 SenseVoice（快）与 Whisper（准）等多种本地模型。

![license](https://img.shields.io/badge/license-MIT-blue) ![platform](https://img.shields.io/badge/platform-Android%207.0%2B-green) ![engine](https://img.shields.io/badge/ASR-sherpa--onnx-orange)

---

## ✨ 功能特性

- 🎙️ **本地语音识别**：sherpa-onnx 离线引擎，不联网、不上传，隐私安全
- 🧩 **模型仓库（不塞进 APK）**：模型存手机存储，App 自动检测。可**下载 / 导入压缩包 / 删除 / 切换**，APK 保持精简
- 🌏 **多语种**：中、英、日、韩、粤；可自动检测或指定
- 🔗 **多种链接**：BV 号 / AV 号 / `b23.tv` 短链 / 分享文案，自动解析
- 📲 **分享入口 + 剪贴板**：B站「分享到本应用」直接开提；打开 App 自动识别剪贴板里的链接
- 🗂️ **历史记录**：每次提取自动入库，可搜索 / 收藏 / 秒开 / 删除
- 🫧 **悬浮球**：后台提取，通知栏看进度
- 💾 **导出**：复制、分享、存 TXT；字幕来源可导出带时间轴的 SRT
- 🎨 **大厂风 UI**：夜间玻璃拟态 + 极光，三页底部导航

## 📸 截图

> 放几张手机截图到 `docs/screenshots/` 并在此引用即可。

---

## 🧱 技术栈

| 组件 | 用途 |
|------|------|
| Kotlin + Jetpack Compose (Material 3) | UI |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) `1.13.0` | 本地语音识别引擎（JNI + ONNX Runtime） |
| SenseVoice-Small / Whisper medium·large-v3 | 可切换的识别模型 |
| OkHttp + kotlinx.serialization | B站 接口与下载 |
| MediaCodec | 音频解码（m4a → 16kHz PCM） |
| ViewModel + StateFlow | 单向数据流 |

**架构**：链接解析 → 取流下载 → 解码 →（可选人声分离）→ 识别，统一走共享的 `TranscriptionPipeline`，主界面与悬浮球复用同一条管线。

## 📂 仓库结构

```
.
├── android-app/                 # 安卓 App（主项目）
│   └── app/src/main/java/com/example/bilitranscript/
│       ├── MainActivity / *Screen.kt     # Compose 三页 UI（提取/历史/设置）
│       ├── MainViewModel.kt              # 状态与动作
│       ├── TranscriptionPipeline.kt      # 共享提取管线
│       ├── BiliDownloader.kt             # B站 解析与音频下载
│       ├── SpeechRecognizer.kt           # sherpa-onnx 识别（assets 或手机文件路径）
│       ├── ModelManager.kt               # 模型仓库：下载/导入/删除/检测
│       └── ...
├── scripts/setup-models.{ps1,sh}# 拉取内置 SenseVoice 模型
├── (Python 桌面原型：app.py / audio_extractor.py 等)
└── README.md
```

---

## 🚀 快速开始（安卓）

### 1. 环境
- Android Studio（含 Android SDK，`compileSdk 36`）
- JDK 21
- 一台 Android 7.0+ 手机（开 USB 调试）

### 2. 拉取内置模型（必需）
内置 SenseVoice 模型 239MB，超 GitHub 单文件上限，**未入库**。clone 后先跑一次：

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File scripts\setup-models.ps1
```
```bash
# macOS / Linux
bash scripts/setup-models.sh
```
> 会把模型放到 `android-app/app/src/main/assets/models/`。sherpa-onnx 的 AAR 已随仓库提供，无需另下。

### 3. 编译安装
```bash
cd android-app
./gradlew assembleDebug
# 安装到已连接的手机
./gradlew installDebug
```
APK 输出在 `android-app/app/build/outputs/apk/debug/app-debug.apk`。

---

## 🧩 模型管理（核心玩法）

模型**不打进 APK**，进 App 后在 **设置 → 模型仓库** 管理。三种获取方式：

| 方式 | 说明 |
|------|------|
| **下载** | App 内直连下载（海外/有代理可用；**国内 HuggingFace 常下不动**） |
| **导入压缩包**（推荐国内） | 电脑/浏览器先下好模型 `.zip` → 传到手机 → 点「📦 导入压缩包」选它，自动解压装好 |
| **adb 推送**（开发者） | 见下方命令，推到 App 私有目录 |

发布时把模型打包成 `.zip`（每个包内直接放 `*.onnx` + `tokens.txt`），作为 **GitHub Release 附件**供下载导入。本仓库的 `scripts` 思路与 `model-packages/`（本地生成、未入库）即为此用途。

可选模型（sherpa-onnx 导出，来源 HuggingFace `csukuangfj/...`）：

| 模型 | 大小(int8) | 特点 |
|------|-----------|------|
| SenseVoice-Small | ~230MB | 快；中英日韩粤；干净人声好 |
| Whisper medium | ~950MB | 更抗噪/音乐、多语种稳；较慢 |
| Whisper large-v3 | ~1.77GB | 最准最抗造；很慢很大 |

<details>
<summary>开发者：adb 推送模型到手机</summary>

```bash
# 以 whisper-medium 为例，把三个文件推进 App 私有目录（debug 包可用 run-as）
adb push medium-encoder.int8.onnx /data/local/tmp/
adb push medium-decoder.int8.onnx /data/local/tmp/
adb push medium-tokens.txt       /data/local/tmp/
adb shell run-as com.example.bilitranscript mkdir -p files/models/whisper-medium
adb shell run-as com.example.bilitranscript cp /data/local/tmp/medium-encoder.int8.onnx files/models/whisper-medium/
adb shell run-as com.example.bilitranscript cp /data/local/tmp/medium-decoder.int8.onnx files/models/whisper-medium/
adb shell run-as com.example.bilitranscript cp /data/local/tmp/medium-tokens.txt       files/models/whisper-medium/
```
</details>

---

## 🖥️ Python 桌面原型

仓库根目录还保留了一个 Flask 网页版（`app.py` / `audio_extractor.py`）：浏览器粘贴链接，用本地 Whisper 或百度云 API 识别。双击 `start.bat` 启动。详见 [docs/python-desktop.md](docs/python-desktop.md)。仅作早期原型，主力已转向安卓端。

## 🗺️ Roadmap

- [ ] 人声分离（去背景音乐）真正接入推理后端
- [ ] VAD 切句：跳过纯音乐段、给所有引擎生成时间轴 → 支持导出 SRT
- [ ] LLM 文案润色（加标点 / 分段 / 摘要 / 翻译）
- [ ] 多平台（抖音 / 快手 / YouTube / 小红书）

## ⚠️ 免责声明

仅供个人学习与研究使用，请遵守 B站 用户协议与相关法律法规，勿用于侵犯版权或商业用途。识别结果由 AI 生成，可能存在错误。

## 📄 License

[MIT](LICENSE)
