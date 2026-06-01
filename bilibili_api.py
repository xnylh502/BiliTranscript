import requests
import json
import os
from urllib.parse import urljoin

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://www.bilibili.com/",
}


def _get_headers_with_cookie() -> dict:
    """如果有 SESSDATA 环境变量或 Cookie 文件，添加 Cookie"""
    headers = dict(HEADERS)
    sessdata = os.environ.get("BILI_SESSDATA", "")
    if not sessdata:
        # 尝试从 cookie.txt 读取
        cookie_path = os.path.join(os.path.dirname(__file__), "cookie.txt")
        if os.path.exists(cookie_path):
            with open(cookie_path, "r", encoding="utf-8") as f:
                sessdata = f.read().strip()
    if sessdata:
        headers["Cookie"] = f"SESSDATA={sessdata}"
    return headers


def get_video_info(bvid: str) -> dict:
    """
    获取视频基本信息（标题、cid等）
    """
    url = "https://api.bilibili.com/x/web-interface/view"
    params = {"bvid": bvid}
    headers = _get_headers_with_cookie()
    resp = requests.get(url, params=params, headers=headers, timeout=15)
    resp.raise_for_status()
    data = resp.json()
    
    if data.get("code") != 0:
        raise RuntimeError(f"获取视频信息失败: {data.get('message', '未知错误')}")
    
    return data["data"]


def get_subtitle_list(bvid: str, cid: int) -> tuple[list[dict], bool]:
    """
    获取视频的字幕列表。
    返回 (字幕列表, 是否需要登录)
    """
    url = "https://api.bilibili.com/x/player/v2"
    params = {"bvid": bvid, "cid": cid}
    headers = _get_headers_with_cookie()
    resp = requests.get(url, params=params, headers=headers, timeout=15)
    resp.raise_for_status()
    data = resp.json()
    
    if data.get("code") != 0:
        raise RuntimeError(f"获取播放器信息失败: {data.get('message', '未知错误')}")
    
    subtitle_info = data["data"].get("subtitle", {})
    subtitles = subtitle_info.get("subtitles", [])
    need_login = data["data"].get("need_login_subtitle", False)
    
    return subtitles, need_login


def download_subtitle(subtitle_url: str) -> list[dict]:
    """
    下载字幕JSON文件，返回body列表（包含content字段）。
    """
    # B站返回的字幕URL有时是//开头，需要补全协议
    if subtitle_url.startswith("//"):
        subtitle_url = "https:" + subtitle_url
    
    resp = requests.get(subtitle_url, headers=HEADERS, timeout=15)
    resp.raise_for_status()
    data = resp.json()
    
    # B站字幕格式: {"body": [{"from": 0.0, "to": 5.0, "content": "xxx", "sid": ...}]}
    return data.get("body", [])


def extract_transcript(bvid: str) -> dict:
    """
    完整流程：输入BV号 → 返回视频标题 + 文案文本
    """
    # 1. 获取视频信息
    info = get_video_info(bvid)
    title = info["title"]
    cid = info["cid"]
    
    # 2. 获取字幕列表
    subtitles, need_login = get_subtitle_list(bvid, cid)
    
    if not subtitles:
        if need_login:
            return {
                "title": title,
                "bvid": bvid,
                "transcript": "",
                "error": "该视频字幕需要登录B站账号才能获取。请设置 BILI_SESSDATA 环境变量，或在目录下创建 cookie.txt 文件写入 SESSDATA 值。"
            }
        return {
            "title": title,
            "bvid": bvid,
            "transcript": "",
            "error": "该视频没有可用的字幕（CC字幕/AI生成字幕）。"
        }
    
    # 3. 优先选择中文字幕（zh-CN/zh-Hans），如果没有则选第一个
    selected = subtitles[0]
    for sub in subtitles:
        lan = sub.get("lan", "").lower()
        if lan in ("zh-cn", "zh-hans", "zh", "chi"):
            selected = sub
            break
    
    # 4. 下载字幕
    subtitle_url = selected.get("subtitle_url", "")
    if not subtitle_url:
        return {
            "title": title,
            "bvid": bvid,
            "transcript": "",
            "error": "字幕链接为空。"
        }
    
    body = download_subtitle(subtitle_url)
    
    if not body:
        return {
            "title": title,
            "bvid": bvid,
            "transcript": "",
            "error": "字幕内容为空。"
        }
    
    # 5. 拼接文案
    from utils import clean_subtitle_text
    transcript = clean_subtitle_text(body)
    
    return {
        "title": title,
        "bvid": bvid,
        "transcript": transcript,
        "subtitle_lang": selected.get("lan_doc", selected.get("lan", "未知")),
        "error": None
    }
