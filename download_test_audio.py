# 下载一个1分钟左右的测试音频
import yt_dlp

URL = "https://www.bilibili.com/video/BV1n3KXz7ERs/"

ydl_opts = {
    'format': 'bestaudio/best',
    'outtmpl': 'test_audio.%(ext)s',
    'quiet': True,
    'postprocessors': [{
        'key': 'FFmpegExtractAudio',
        'preferredcodec': 'mp3',
        'preferredquality': '192',
    }],
    'ffmpeg_location': '.',
}

with yt_dlp.YoutubeDL(ydl_opts) as ydl:
    info = ydl.extract_info(URL, download=True)
    print(f"下载完成: {info.get('title')}")
    print(f"时长: {info.get('duration')} 秒")
