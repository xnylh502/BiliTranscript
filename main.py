#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B站视频文案提取器
用法:
    python main.py
    然后粘贴B站分享链接，如:
    【KARDS全隐藏勋章获得教程】 https://www.bilibili.com/video/BV1n3KXz7ERs/?share_source=copy_web

输出:
    output/视频标题.txt
"""

import os
import sys
from pathlib import Path

from utils import extract_url_from_share_text, extract_bvid_from_url
from bilibili_api import extract_transcript

OUTPUT_DIR = Path("output")


def sanitize_filename(name: str) -> str:
    """去除文件名中的非法字符"""
    invalid_chars = '\\/:*?"<>|'
    for ch in invalid_chars:
        name = name.replace(ch, '_')
    return name.strip()


def main():
    OUTPUT_DIR.mkdir(exist_ok=True)
    
    print("=" * 50)
    print("  B站视频文案提取器")
    print("=" * 50)
    print()
    print("提示: 直接粘贴B站分享文本即可，例如:")
    print('  【KARDS全隐藏勋章获得教程】 https://www.bilibili.com/video/BV1n3KXz7ERs/')
    print()
    
    raw_input = input("请输入B站视频链接或分享文本:\n").strip()
    
    if not raw_input:
        print("[错误] 输入为空。")
        sys.exit(1)
    
    # 从分享文本中提取URL
    url = extract_url_from_share_text(raw_input)
    if not url:
        # 用户可能直接输入了URL
        url = raw_input
    
    # 提取BV号
    bvid = extract_bvid_from_url(url)
    if not bvid:
        print(f"[错误] 无法从输入中识别BV号。输入内容: {raw_input[:100]}")
        sys.exit(1)
    
    print(f"\n识别到BV号: {bvid}")
    print("正在获取视频信息和字幕...")
    
    try:
        result = extract_transcript(bvid)
    except Exception as e:
        print(f"[错误] 获取文案失败: {e}")
        sys.exit(1)
    
    if result.get("error"):
        print(f"\n[提示] {result['error']}")
        print(f"视频标题: {result['title']}")
        sys.exit(0)
    
    title = result["title"]
    transcript = result["transcript"]
    lang = result.get("subtitle_lang", "未知语言")
    
    print(f"\n✅ 视频标题: {title}")
    print(f"   字幕语言: {lang}")
    print(f"   文案字数: {len(transcript)}")
    
    # 保存到文件
    filename = sanitize_filename(f"{title}.txt")
    filepath = OUTPUT_DIR / filename
    
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(f"标题: {title}\n")
        f.write(f"BV号: {bvid}\n")
        f.write(f"字幕语言: {lang}\n")
        f.write("=" * 40 + "\n\n")
        f.write(transcript)
    
    print(f"\n💾 文案已保存到: {filepath}")
    print("\n--- 文案预览 (前300字) ---")
    print(transcript[:300] + ("..." if len(transcript) > 300 else ""))


if __name__ == "__main__":
    main()
