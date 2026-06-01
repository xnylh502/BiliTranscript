#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B站视频音频下载 + Whisper语音识别
"""

import os
import sys
import tempfile
import shutil
import warnings

import yt_dlp

# 尝试导入Whisper（可能未安装）
try:
    import whisper
    WHISPER_AVAILABLE = True
    warnings.filterwarnings("ignore", message="FP16 is not supported on CPU")
except ImportError:
    WHISPER_AVAILABLE = False

# 模型配置
MODEL_SIZE = "small"
MODELS_DIR = os.path.join(os.path.dirname(__file__), "models")

# Whisper模型（延迟加载，单例）
_model = None

def get_model():
    """获取Whisper模型（单例，从项目目录加载）"""
    global _model
    if _model is None:
        if not WHISPER_AVAILABLE:
            raise RuntimeError("Whisper未安装，请运行: pip install openai-whisper")
        print(f"[Whisper] 正在加载 {MODEL_SIZE} 模型...")
        _model = whisper.load_model(MODEL_SIZE, download_root=MODELS_DIR)
        print("[Whisper] 模型加载完成")
    return _model


def get_ffmpeg_path() -> str:
    """获取ffmpeg路径（优先项目目录下的ffmpeg.exe）"""
    local_ffmpeg = os.path.join(os.path.dirname(__file__), "ffmpeg.exe")
    if os.path.exists(local_ffmpeg):
        return local_ffmpeg
    for path_dir in os.environ.get("PATH", "").split(os.pathsep):
        candidate = os.path.join(path_dir, "ffmpeg.exe")
        if os.path.exists(candidate):
            return candidate
    return "ffmpeg"


def download_audio(bvid: str, output_dir: str, need_ffmpeg: bool = True) -> tuple[str, dict]:
    """
    下载B站视频音频。
    返回: (音频文件路径, 视频信息字典)
    """
    url = f"https://www.bilibili.com/video/{bvid}/"
    output_template = os.path.join(output_dir, "%(id)s.%(ext)s")
    
    ydl_opts = {
        'format': 'bestaudio/best',
        'outtmpl': output_template,
        'quiet': True,
        'no_warnings': True,
    }
    
    if need_ffmpeg:
        ffmpeg_path = get_ffmpeg_path()
        if os.path.exists(ffmpeg_path):
            ydl_opts['ffmpeg_location'] = os.path.dirname(ffmpeg_path)
        # 转码为mp3
        ydl_opts['postprocessors'] = [{
            'key': 'FFmpegExtractAudio',
            'preferredcodec': 'mp3',
            'preferredquality': '192',
        }]
    
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
    
    expected_id = info.get('id', bvid)
    for ext in ['mp3', 'm4a', 'webm', 'opus']:
        candidate = os.path.join(output_dir, f"{expected_id}.{ext}")
        if os.path.exists(candidate):
            return candidate, info
    
    for f in os.listdir(output_dir):
        if f.startswith(expected_id):
            return os.path.join(output_dir, f), info
    
    raise FileNotFoundError("音频下载后找不到文件")


def transcribe_whisper(audio_path: str) -> str:
    """用Whisper转录音频为文字"""
    model = get_model()
    ffmpeg_path = get_ffmpeg_path()
    if os.path.exists(ffmpeg_path):
        ffmpeg_dir = os.path.dirname(ffmpeg_path)
        current_path = os.environ.get("PATH", "")
        if ffmpeg_dir not in current_path:
            os.environ["PATH"] = ffmpeg_dir + os.pathsep + current_path
    
    result = model.transcribe(audio_path, language="zh", verbose=False)
    return result["text"].strip()


def transcribe_baidu(audio_path: str) -> str:
    """用百度语音API转录音频为文字"""
    from cloud_asr import extract_transcript_baidu
    return extract_transcript_baidu(audio_path)


def extract_from_bilibili(bvid: str, progress_callback=None) -> dict:
    """
    完整流程：下载音频 → 识别 → 返回文案
    优先使用百度API（如果配置了密钥），否则用本地Whisper
    """
    tmp_dir = tempfile.mkdtemp(prefix="bili_audio_")
    
    try:
        # 检查是否配置了百度API
        from cloud_asr import is_configured
        use_baidu = is_configured()
        
        if progress_callback:
            progress_callback("download", "正在下载视频音频...")
        
        # 如果用百度API，不需要ffmpeg转码（支持m4a原生格式）
        audio_path, info = download_audio(bvid, tmp_dir, need_ffmpeg=not use_baidu)
        title = info.get('title', '未知标题')
        duration = info.get('duration', 0)
        
        if use_baidu:
            if progress_callback:
                progress_callback("transcribe", "正在用百度AI识别语音...（云端处理，速度更快）")
            transcript = transcribe_baidu(audio_path)
        else:
            if progress_callback:
                progress_callback("transcribe", "正在本地识别语音...（首次使用需加载AI模型）")
            if not WHISPER_AVAILABLE:
                raise RuntimeError("Whisper未安装且未配置百度API。请安装Whisper或配置百度API密钥。")
            transcript = transcribe_whisper(audio_path)
        
        if progress_callback:
            progress_callback("done", "提取完成！")
        
        return {
            "success": True,
            "title": title,
            "bvid": bvid,
            "duration": duration,
            "transcript": transcript,
            "word_count": len(transcript),
            "engine": "baidu" if use_baidu else "whisper",
            "error": None
        }
        
    except yt_dlp.utils.DownloadError as e:
        error_msg = str(e)
        if "Private video" in error_msg or "login" in error_msg.lower():
            error_msg = "该视频需要登录才能访问，或已被删除/设为私密"
        elif "Unable to extract" in error_msg:
            error_msg = "无法解析视频信息，请检查BV号是否正确"
        
        if progress_callback:
            progress_callback("error", error_msg)
        return {"success": False, "error": error_msg, "title": "", "bvid": bvid, "transcript": ""}
        
    except Exception as e:
        error_msg = f"处理失败: {str(e)}"
        if progress_callback:
            progress_callback("error", error_msg)
        return {"success": False, "error": error_msg, "title": "", "bvid": bvid, "transcript": ""}
        
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)
