#!/usr/bin/env python3
"""下载Whisper medium模型"""
import os
import whisper

MODELS_DIR = os.path.join(os.path.dirname(__file__), "models")
os.makedirs(MODELS_DIR, exist_ok=True)

print("正在下载 Whisper medium 模型...")
print("模型大小约: 769MB")
print("这可能需要几分钟，请耐心等待...")
print()

model = whisper.load_model("medium", download_root=MODELS_DIR)
print("\n[OK] medium 模型下载完成！")
