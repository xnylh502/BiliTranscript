#!/usr/bin/env bash
# 拉取「内置 SenseVoice 模型」到 assets。
# 该模型 239MB，超 GitHub 100MB 单文件上限，故未入库；编译出可用 App 前需先跑本脚本。
# Whisper 等更大的模型不在这里——进 App 后用「设置 → 模型仓库 → 下载 / 导入压缩包」获取。
#
# 用法（在仓库根目录）：  bash scripts/setup-models.sh
set -euo pipefail

BASE="https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
DEST="$(cd "$(dirname "$0")/.." && pwd)/android-app/app/src/main/assets/models"
mkdir -p "$DEST"

echo "下载 SenseVoice 模型到 $DEST ..."
curl -L "$BASE/model.int8.onnx" -o "$DEST/model.onnx"
curl -L "$BASE/tokens.txt"       -o "$DEST/tokens.txt"
echo "完成。现在可以用 Android Studio 打开 android-app 编译，或运行 ./android-app/gradlew assembleDebug"
