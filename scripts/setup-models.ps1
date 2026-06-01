# 拉取「内置 SenseVoice 模型」到 assets。
# 该模型 239MB，超 GitHub 100MB 单文件上限，故未入库；编译出可用 App 前需先跑本脚本。
# Whisper 等更大的模型不在这里——进 App 后用「设置 → 模型仓库 → 下载 / 导入压缩包」获取。
#
# 用法（在仓库根目录）：  powershell -ExecutionPolicy Bypass -File scripts\setup-models.ps1
# 国内若 HuggingFace 慢/不通，可挂代理，或手动把 model.int8.onnx 重命名为 model.onnx 放进 assets\models\。

$ErrorActionPreference = "Stop"
$base = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
$dest = Join-Path $PSScriptRoot "..\android-app\app\src\main\assets\models"
New-Item -ItemType Directory -Force $dest | Out-Null

Write-Host "下载 SenseVoice 模型到 $dest ..."
Invoke-WebRequest "$base/model.int8.onnx" -OutFile (Join-Path $dest "model.onnx")
Invoke-WebRequest "$base/tokens.txt"       -OutFile (Join-Path $dest "tokens.txt")
Write-Host "完成。现在可以用 Android Studio 打开 android-app 编译，或运行 .\android-app\gradlew assembleDebug"
