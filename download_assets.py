#!/usr/bin/env python3
import os
import sys
import urllib.request
import json

AAR_DIR = "android-app/app/libs"
MODEL_DIR = "android-app/app/src/main/assets/models"
os.makedirs(AAR_DIR, exist_ok=True)
os.makedirs(MODEL_DIR, exist_ok=True)

def download(url, dest):
    print(f"Downloading: {url}")
    print(f"  -> {dest}")
    try:
        urllib.request.urlretrieve(url, dest)
        size = os.path.getsize(dest) / 1024 / 1024
        print(f"  OK ({size:.1f} MB)")
        return True
    except Exception as e:
        print(f"  FAILED: {e}")
        return False

# 1. Download sherpa-onnx AAR
print("=" * 50)
print("Step 1: sherpa-onnx AAR")
print("=" * 50)

try:
    api_url = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/latest"
    with urllib.request.urlopen(api_url, timeout=15) as r:
        data = json.loads(r.read().decode())
    tag = data.get("tag_name", "")
    print(f"Latest version: {tag}")
    
    for asset in data.get("assets", []):
        name = asset.get("name", "")
        if "android" in name.lower() and name.endswith(".aar"):
            download(asset["browser_download_url"], os.path.join(AAR_DIR, name))
            break
    else:
        print("No Android AAR found in release. Please download manually:")
        print(f"  https://github.com/k2-fsa/sherpa-onnx/releases/tag/{tag}")
except Exception as e:
    print(f"Failed to query release: {e}")

# 2. Download SenseVoice ONNX model
print("\n" + "=" * 50)
print("Step 2: SenseVoice ONNX Model")
print("=" * 50)

# Try multiple sources
sources = [
    ("https://huggingface.co/mingl/Sensevoice_Api/resolve/main/model.onnx", "model.onnx"),
    ("https://huggingface.co/mingl/Sensevoice_Api/resolve/main/model_quant.onnx", "model_quant.onnx"),
    ("https://huggingface.co/mingl/Sensevoice_Api/resolve/main/tokens.txt", "tokens.txt"),
    ("https://huggingface.co/mingl/Sensevoice_Api/resolve/main/config.yaml", "config.yaml"),
]

for url, filename in sources:
    dest = os.path.join(MODEL_DIR, filename)
    if os.path.exists(dest):
        sz = os.path.getsize(dest) / 1024 / 1024
        print(f"SKIP {filename} ({sz:.1f} MB already exists)")
        continue
    if not download(url, dest):
        print(f"  Try alternative URL...")
        alt_url = url.replace("huggingface.co", "hf-mirror.com")
        download(alt_url, dest)

print("\n" + "=" * 50)
print("Done.")
print("=" * 50)
