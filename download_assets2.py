#!/usr/bin/env python3
import os
import urllib.request
import json

AAR_DIR = "android-app/app/libs"
MODEL_DIR = "android-app/app/src/main/assets/models"
os.makedirs(AAR_DIR, exist_ok=True)
os.makedirs(MODEL_DIR, exist_ok=True)

def download(url, dest, timeout=120):
    print(f"Downloading: {url}")
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            with open(dest, 'wb') as f:
                f.write(resp.read())
        sz = os.path.getsize(dest) / 1024 / 1024
        print(f"  OK ({sz:.1f} MB)")
        return True
    except Exception as e:
        print(f"  FAIL: {e}")
        return False

# 1. sherpa-onnx AAR from GitHub
print("=" * 50)
print("Step 1: sherpa-onnx AAR")
print("=" * 50)

# Direct known URL for v1.13.1
aar_urls = [
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.1/sherpa-onnx-v1.13.1-android-arm64-v8a.aar",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.0/sherpa-onnx-v1.13.0-android-arm64-v8a.aar",
]

aar_downloaded = False
for url in aar_urls:
    name = url.split('/')[-1]
    dest = os.path.join(AAR_DIR, name)
    if download(url, dest, timeout=300):
        aar_downloaded = True
        break

if not aar_downloaded:
    print("AAR download failed. Please manually download from:")
    print("  https://github.com/k2-fsa/sherpa-onnx/releases")

# 2. SenseVoice model from ModelScope (China mirror)
print("\n" + "=" * 50)
print("Step 2: SenseVoice model from ModelScope")
print("=" * 50)

# ModelScope file download API
modelscope_base = "https://www.modelscope.cn/api/v1/models/iic/SenseVoiceSmall/repo"

# Try direct file URLs first
files_to_download = {
    "model.onnx": "https://www.modelscope.cn/models/iic/SenseVoiceSmall/resolve/master/model.onnx",
    "tokens.txt": "https://www.modelscope.cn/models/iic/SenseVoiceSmall/resolve/master/tokens.txt",
}

# Actually SenseVoice uses FunASR format, let me try another known repo
# mingl/Sensevoice_Api on modelscope
alt_files = {
    "model.onnx": "https://www.modelscope.cn/models/mingl/Sensevoice_Api/resolve/master/model.onnx",
    "tokens.txt": "https://www.modelscope.cn/models/mingl/Sensevoice_Api/resolve/master/tokens.txt",
    "model_quant.onnx": "https://www.modelscope.cn/models/mingl/Sensevoice_Api/resolve/master/model_quant.onnx",
}

for filename, url in alt_files.items():
    dest = os.path.join(MODEL_DIR, filename)
    if os.path.exists(dest):
        sz = os.path.getsize(dest) / 1024 / 1024
        print(f"SKIP {filename} ({sz:.1f}MB exists)")
        continue
    if not download(url, dest, timeout=300):
        print(f"  Could not download {filename}")

print("\n" + "=" * 50)
print("Done. Check results above.")
print("=" * 50)
