#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B站视频文案提取器 - Web版（语音识别版）
运行: python app.py
"""

import os
import sys
import webbrowser
import threading
import time
from flask import Flask, render_template, request, jsonify

from utils import extract_url_from_share_text, extract_bvid_from_url, sanitize_filename
from audio_extractor import extract_from_bilibili, get_model

app = Flask(__name__)


# 启动时预加载Whisper模型（避免第一次请求时等待）
@app.before_request
def preload_model():
    """懒加载模型：第一个请求触发加载"""
    pass  # 模型会在extract_from_bilibili中自动加载


@app.route("/")
def index():
    return render_template("index.html")


@app.route("/api/extract", methods=["POST"])
def api_extract():
    data = request.get_json() or {}
    raw_input = data.get("url", "").strip()
    
    if not raw_input:
        return jsonify({"success": False, "error": "请输入B站视频链接"}), 400
    
    url = extract_url_from_share_text(raw_input)
    if not url:
        url = raw_input
    
    bvid = extract_bvid_from_url(url)
    if not bvid:
        return jsonify({"success": False, "error": "无法从链接中识别BV号，请检查链接格式"}), 400
    
    try:
        result = extract_from_bilibili(bvid)
    except Exception as e:
        return jsonify({"success": False, "error": f"服务器错误: {str(e)}"}), 500
    
    if not result.get("success"):
        return jsonify({
            "success": False,
            "error": result.get("error", "提取失败"),
            "title": result.get("title", ""),
            "bvid": result.get("bvid", "")
        })
    
    return jsonify({
        "success": True,
        "title": result["title"],
        "bvid": result["bvid"],
        "duration": result.get("duration", 0),
        "transcript": result["transcript"],
        "word_count": result.get("word_count", 0)
    })


@app.route("/api/download", methods=["POST"])
def api_download():
    data = request.get_json() or {}
    title = data.get("title", "文案")
    bvid = data.get("bvid", "")
    transcript = data.get("transcript", "")
    
    filename = sanitize_filename(f"{title}.txt")
    
    content = f"标题: {title}\nBV号: {bvid}\n{'='*40}\n\n{transcript}"
    
    from flask import Response
    return Response(
        content,
        mimetype="text/plain; charset=utf-8",
        headers={"Content-Disposition": f"attachment; filename={filename}"}
    )


@app.route("/api/config", methods=["GET"])
def api_config_get():
    """获取当前API配置状态"""
    from cloud_asr import is_configured
    return jsonify({
        "baidu_configured": is_configured()
    })


@app.route("/api/config", methods=["POST"])
def api_config_post():
    """保存百度API配置"""
    data = request.get_json() or {}
    app_id = data.get("app_id", "").strip()
    api_key = data.get("api_key", "").strip()
    secret_key = data.get("secret_key", "").strip()
    
    key_file = os.path.join(os.path.dirname(__file__), "baidu_key.txt")
    
    if app_id and api_key and secret_key:
        # 保存配置
        with open(key_file, "w", encoding="utf-8") as f:
            f.write(f"{app_id}\n{api_key}\n{secret_key}\n")
        return jsonify({"success": True, "message": "百度API配置已保存，将自动使用云端识别"})
    else:
        # 清空配置
        if os.path.exists(key_file):
            os.remove(key_file)
        return jsonify({"success": True, "message": "已清除百度API配置，回退到本地Whisper"})


def open_browser():
    time.sleep(2)
    webbrowser.open("http://127.0.0.1:5000/")


if __name__ == "__main__":
    print("=" * 50)
    print("  B站视频文案提取器 - Web版")
    print("=" * 50)
    print("\n正在启动服务器...")
    print("浏览器将自动打开 http://127.0.0.1:5000")
    print("按 Ctrl+C 停止服务\n")
    
    threading.Timer(2.0, open_browser).start()
    
    app.run(host="127.0.0.1", port=5000, debug=False, threaded=True)
