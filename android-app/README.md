# B站文案提取器 (Android)

基于 **sherpa-onnx + SenseVoice** 的完全离线语音识别Android应用，从B站视频声音中提取文字。

> **v2 已大改**（共享管线 + 三页大厂风 UI + 历史/设置 + 字幕优先 + 可插拔 Whisper/人声分离）。
> 新功能与「如何放大模型/分离模型」详见 **[UPGRADE.md](UPGRADE.md)**。下面是 v1 基础说明。

## 功能

- 粘贴B站视频链接（支持BV号、AV号、短链接）
- 自动下载视频音频（m4a格式）
- 离线语音识别转文字（无需网络）
- 支持中文、英文、日语、韩语、粤语
- 一键复制/分享文案

## 技术栈

| 组件 | 说明 |
|------|------|
| **sherpa-onnx v1.13.0** | 本地语音识别引擎（JNI） |
| **SenseVoice-Small** | 阿里开源多语言语音识别模型（INT8量化，228MB） |
| **Jetpack Compose** | UI框架 |
| **MediaCodec** | 音频解码（m4a → 16kHz PCM） |
| **OkHttp** | 网络请求（仅用于下载视频） |

## APK信息

- **文件**: `app/build/outputs/apk/debug/app-debug.apk`
- **大小**: ~306MB（含模型228MB）
- **minSdk**: 24 (Android 7.0)
- **targetSdk**: 36

## 核心代码

```
app/src/main/java/com/example/bilitranscript/
├── MainActivity.kt        # Compose UI主界面
├── MainViewModel.kt       # 状态管理（下载→解码→识别）
├── BiliDownloader.kt      # B站视频音频下载
├── AudioDecoder.kt        # MediaCodec音频解码
├── SpeechRecognizer.kt    # sherpa-onnx语音识别封装
└── theme/                 # Material3主题
```

## 使用方式

1. 安装APK到手机
2. 打开应用，等待模型初始化（首次启动会复制模型到私有目录）
3. 粘贴B站视频链接（如 `https://www.bilibili.com/video/BV1n3KXz7ERs/`）
4. 点击"一键提取文案"
5. 等待下载→解码→识别完成
6. 复制或分享识别结果

## 模型文件

模型内置在APK的 `assets/models/` 中：
- `model.onnx` - SenseVoice量化模型（228MB）
- `tokens.txt` - 词表文件（333KB）

来源: [ModelScope - adihzgnaz/sherpa-onnx-sensevoice-small](https://www.modelscope.cn/adihzgnaz/sherpa-onnx-sensevoice-small)

## 构建

```bash
# 需要: JDK 21, Android SDK
./gradlew assembleDebug
```

## 注意事项

- 首次启动需约10-30秒复制模型文件（取决于手机性能）
- 模型加载后内存占用约1-2GB
- 语音识别速度：RTF约0.06（1分钟音频约3-4秒完成）
- 仅提取声音文字，不下载视频画面
