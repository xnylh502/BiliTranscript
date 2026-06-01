#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
百度语音API封装 - 音频文件转写（长语音识别）
官网: https://ai.baidu.com/tech/speech/asr

使用流程：
1. 去 https://ai.baidu.com/tech/speech/asr 创建应用
2. 实名认证后领取免费额度
3. 获取 AppID、API Key、Secret Key
4. 配置到环境变量或 baidu_key.txt 文件中
"""

import os
import sys
import json
import base64
import time
import requests
from typing import Optional


def _get_credentials() -> tuple[str, str, str]:
    """
    获取百度API认证信息。
    优先级：环境变量 > baidu_key.txt 文件
    """
    # 方式1: 环境变量
    app_id = os.environ.get("BAIDU_APP_ID", "").strip()
    api_key = os.environ.get("BAIDU_API_KEY", "").strip()
    secret_key = os.environ.get("BAIDU_SECRET_KEY", "").strip()
    
    if app_id and api_key and secret_key:
        return app_id, api_key, secret_key
    
    # 方式2: baidu_key.txt 文件
    key_file = os.path.join(os.path.dirname(__file__), "baidu_key.txt")
    if os.path.exists(key_file):
        with open(key_file, "r", encoding="utf-8") as f:
            lines = [line.strip() for line in f.readlines() if line.strip() and not line.startswith("#")]
        if len(lines) >= 3:
            return lines[0], lines[1], lines[2]
    
    return "", "", ""


def _get_access_token(api_key: str, secret_key: str) -> str:
    """通过API Key和Secret Key获取access_token"""
    url = "https://aip.baidubce.com/oauth/2.0/token"
    params = {
        "grant_type": "client_credentials",
        "client_id": api_key,
        "client_secret": secret_key,
    }
    resp = requests.post(url, params=params, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    
    if "access_token" not in data:
        raise RuntimeError(f"获取access_token失败: {data.get('error_description', '未知错误')}")
    
    return data["access_token"]


def _read_audio_base64(audio_path: str) -> str:
    """读取音频文件并转为base64"""
    with open(audio_path, "rb") as f:
        return base64.b64encode(f.read()).decode("utf-8")


def submit_task(audio_path: str, access_token: str) -> str:
    """
    提交音频转写任务。
    返回 task_id
    """
    url = f"https://aip.baidubce.com/rpc/2.0/aasr/v1/create?access_token={access_token}"
    
    # 音频格式
    ext = os.path.splitext(audio_path)[1].lower().replace(".", "")
    if ext not in ("wav", "mp3", "pcm", "m4a", "amr"):
        ext = "m4a"  # 默认按m4a处理
    
    speech_base64 = _read_audio_base64(audio_path)
    
    payload = {
        "speech": speech_base64,
        "format": ext,
        "pid": 80001,  # 中文普通话近场识别
        "rate": 16000,
    }
    
    headers = {"Content-Type": "application/json"}
    resp = requests.post(url, headers=headers, data=json.dumps(payload), timeout=60)
    resp.raise_for_status()
    data = resp.json()
    
    if "task_id" not in data:
        err_msg = data.get("err_msg", "未知错误")
        err_no = data.get("err_no", -1)
        raise RuntimeError(f"提交任务失败 [{err_no}]: {err_msg}")
    
    return data["task_id"]


def query_task(task_id: str, access_token: str) -> dict:
    """
    查询任务状态。
    返回完整结果字典
    """
    url = f"https://aip.baidubce.com/rpc/2.0/aasr/v1/query?access_token={access_token}"
    
    payload = {"task_ids": [task_id]}
    headers = {"Content-Type": "application/json"}
    
    resp = requests.post(url, headers=headers, data=json.dumps(payload), timeout=30)
    resp.raise_for_status()
    data = resp.json()
    
    return data


def extract_transcript_baidu(audio_path: str) -> str:
    """
    使用百度语音API识别音频，返回文案文本。
    
    流程：
    1. 获取API凭证
    2. 获取access_token
    3. 提交任务
    4. 轮询查询结果（最多30次，每次等待3秒）
    5. 拼接文案
    """
    app_id, api_key, secret_key = _get_credentials()
    
    if not all((app_id, api_key, secret_key)):
        raise RuntimeError(
            "未配置百度API密钥。请按以下方式配置：\n"
            "1. 去 https://ai.baidu.com/tech/speech/asr 创建应用\n"
            "2. 实名认证后领取免费额度\n"
            "3. 获取 AppID、API Key、Secret Key\n"
            "4. 在项目目录下创建 baidu_key.txt，每行一个：\n"
            "   第一行: AppID\n"
            "   第二行: API Key\n"
            "   第三行: Secret Key\n"
            "或设置环境变量: BAIDU_APP_ID, BAIDU_API_KEY, BAIDU_SECRET_KEY"
        )
    
    # 1. 获取access_token
    access_token = _get_access_token(api_key, secret_key)
    
    # 2. 提交任务
    print("[百度API] 提交音频转写任务...")
    task_id = submit_task(audio_path, access_token)
    print(f"[百度API] 任务ID: {task_id}")
    
    # 3. 轮询查询结果
    max_retries = 30
    for i in range(max_retries):
        time.sleep(3)
        result = query_task(task_id, access_token)
        
        tasks = result.get("tasks_info", [])
        if not tasks:
            continue
        
        task = tasks[0]
        status = task.get("task_status")
        
        if status == "Success":
            # 识别成功，提取文案
            results = task.get("task_result", {}).get("result", [])
            transcript = "\n".join(r for r in results if r)
            print(f"[百度API] 识别完成，共 {len(results)} 句")
            return transcript
        
        elif status == "Failure":
            err_msg = task.get("err_msg", "未知错误")
            raise RuntimeError(f"百度API识别失败: {err_msg}")
        
        elif status in ("Running", "Waiting"):
            print(f"[百度API] 识别中... ({i+1}/{max_retries})")
            continue
    
    raise RuntimeError("百度API识别超时，请稍后重试")


def is_configured() -> bool:
    """检查是否已配置百度API密钥"""
    app_id, api_key, secret_key = _get_credentials()
    return bool(app_id and api_key and secret_key)
