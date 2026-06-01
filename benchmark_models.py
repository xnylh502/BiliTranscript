#!/usr/bin/env python3
"""测试不同Whisper模型的识别速度"""
import time
import os
import whisper

MODELS_DIR = os.path.join(os.path.dirname(__file__), "models")
AUDIO_FILE = "test_audio.m4a"  # 先用之前下载的音频测试

def benchmark_model(model_name, audio_path):
    """测试单个模型的加载时间和识别速度"""
    print(f"\n{'='*50}")
    print(f"测试模型: {model_name}")
    print(f"{'='*50}")
    
    # 测试加载时间
    t0 = time.time()
    print(f"[1/3] 加载模型中...")
    model = whisper.load_model(model_name, download_root=MODELS_DIR)
    load_time = time.time() - t0
    print(f"      加载完成，耗时: {load_time:.1f} 秒")
    
    # 测试识别时间
    print(f"[2/3] 开始语音识别...")
    t1 = time.time()
    result = model.transcribe(audio_path, language="zh", verbose=False)
    infer_time = time.time() - t1
    print(f"      识别完成，耗时: {infer_time:.1f} 秒")
    
    # 输出结果
    word_count = len(result["text"])
    print(f"[3/3] 结果: {word_count} 字")
    print(f"      前100字: {result['text'][:100]}...")
    
    return {
        "model": model_name,
        "load_time": load_time,
        "infer_time": infer_time,
        "word_count": word_count
    }

def main():
    if not os.path.exists(AUDIO_FILE):
        print(f"错误: 找不到测试音频 {AUDIO_FILE}")
        print("请先运行程序提取一个视频，或者手动准备一个音频文件")
        return
    
    import subprocess
    # 获取音频时长
    ffmpeg_path = os.path.join(os.path.dirname(__file__), "ffmpeg.exe")
    result = subprocess.run([ffmpeg_path, "-i", AUDIO_FILE], capture_output=True, text=True)
    # 从stderr中解析时长（ffmpeg的输出在stderr中）
    duration_str = "未知"
    for line in result.stderr.split('\n'):
        if "Duration" in line:
            duration_str = line.strip()
            break
    
    print(f"测试音频: {AUDIO_FILE}")
    print(f"音频信息: {duration_str}")
    print(f"\n即将测试 base 和 small 模型的速度对比...")
    print("(medium 模型需要下载 769MB，如果测试会额外标注)")
    
    results = []
    
    # 测试 base（已下载）
    if os.path.exists(os.path.join(MODELS_DIR, "base.pt")):
        results.append(benchmark_model("base", AUDIO_FILE))
    else:
        print("\n[跳过] base 模型未找到")
    
    # 测试 small（已下载）
    if os.path.exists(os.path.join(MODELS_DIR, "small.pt")):
        results.append(benchmark_model("small", AUDIO_FILE))
    else:
        print("\n[跳过] small 模型未找到")
    
    # 汇总
    print(f"\n{'='*50}")
    print("速度对比汇总")
    print(f"{'='*50}")
    for r in results:
        print(f"{r['model']:8s} | 加载: {r['load_time']:5.1f}s | 识别: {r['infer_time']:5.1f}s | 字数: {r['word_count']}")
    
    if len(results) >= 2:
        ratio = results[-1]['infer_time'] / results[0]['infer_time']
        print(f"\n结论: {results[-1]['model']} 比 {results[0]['model']} 慢约 {ratio:.1f} 倍")

if __name__ == "__main__":
    main()
