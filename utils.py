import re
from urllib.parse import urlparse, parse_qs


def sanitize_filename(name: str) -> str:
    """去除文件名中的非法字符"""
    invalid_chars = '\\/:*?"<>|'
    for ch in invalid_chars:
        name = name.replace(ch, '_')
    return name.strip()


def extract_url_from_share_text(text: str) -> str | None:
    """
    从B站分享文本中提取URL。
    示例: 【KARDS全隐藏勋章获得教程】 https://www.bilibili.com/video/BV1n3KXz7ERs/...
    """
    # 匹配 http/https URL
    url_pattern = re.compile(r'https?://[^\s\u3000\uff08\uff09\[\]【】]+')
    match = url_pattern.search(text)
    if match:
        url = match.group(0)
        # 去除末尾的中文标点或特殊字符
        url = url.rstrip('，。！？、；：""''（）【】')
        return url
    return None


def extract_bvid_from_url(url: str) -> str | None:
    """
    从B站视频URL中提取BV号。
    支持: /video/BVxxx, /BVxxx, ?bvid=BVxxx
    """
    # 路径中的BV号
    bvid_pattern = re.compile(r'/(BV[1-9A-HJ-NP-Za-km-z]{10})')
    match = bvid_pattern.search(url)
    if match:
        return match.group(1)
    
    # query参数中的bvid
    parsed = urlparse(url)
    query = parse_qs(parsed.query)
    if 'bvid' in query:
        return query['bvid'][0]
    
    return None


def clean_subtitle_text(body: list[dict]) -> str:
    """
    将字幕JSON body拼接成完整文案，去除时间戳，合并同一句。
    """
    lines = []
    for item in body:
        content = item.get("content", "").strip()
        if not content:
            continue
        # 简单合并：如果当前行和前一行是连续的短句，可以不加换行
        lines.append(content)
    
    # 将字幕合并为自然段落
    # 策略：如果一行很短（<=10字）且不以标点结尾，尝试和下一句合并
    result = []
    buffer = ""
    for line in lines:
        if buffer:
            # 如果buffer不以句号/问号/感叹号/省略号结尾，且当前行首字不是标点
            if not re.search(r'[。！？\.\?\!…]$', buffer):
                buffer += line
            else:
                result.append(buffer)
                buffer = line
        else:
            buffer = line
    
    if buffer:
        result.append(buffer)
    
    return "\n".join(result)
