#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B站文案提取器 - 启动器
"""

import subprocess
import sys
import os
import webbrowser
import time
import io

# Windows: 设置控制台为UTF-8编码
if sys.platform == "win32":
    os.system("chcp 65001 >nul")
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")


def print_banner():
    print("=" * 50)
    print("  B站视频文案提取器")
    print("=" * 50)
    print()


def check_python():
    version = sys.version_info
    if version.major < 3 or (version.major == 3 and version.minor < 8):
        print(f"[错误] Python版本过低: {version.major}.{version.minor}")
        print("需要 Python 3.8 或更高版本")
        print("下载地址: https://www.python.org/downloads/")
        input("\n按 Enter 退出...")
        return False
    print(f"[OK] Python {version.major}.{version.minor}.{version.micro}")
    return True


def install_dependencies():
    print("[1/3] 正在检查依赖...")
    req_file = os.path.join(os.path.dirname(__file__), "requirements.txt")

    if not os.path.exists(req_file):
        print("[警告] 未找到 requirements.txt")
        return True

    result = subprocess.run(
        [sys.executable, "-m", "pip", "install", "-q", "-r", "requirements.txt"],
        capture_output=True
    )

    if result.returncode != 0:
        print("[提示] 静默安装失败，尝试完整安装...")
        result = subprocess.run(
            [sys.executable, "-m", "pip", "install", "-r", "requirements.txt"]
        )
        if result.returncode != 0:
            print("[错误] 依赖安装失败，请检查网络连接")
            input("\n按 Enter 退出...")
            return False

    print("[OK] 依赖检查完成")
    return True


def start_server():
    print("[2/3] 正在启动服务...")
    print("[3/3] 浏览器即将自动打开...")
    print()
    print("服务地址: http://127.0.0.1:5000")
    print("按 Ctrl+C 停止服务")
    print()

    def open_browser():
        time.sleep(2)
        webbrowser.open("http://127.0.0.1:5000/")

    import threading
    threading.Thread(target=open_browser, daemon=True).start()

    app_path = os.path.join(os.path.dirname(__file__), "app.py")
    subprocess.run([sys.executable, app_path])


def main():
    print_banner()

    if not check_python():
        sys.exit(1)

    if not install_dependencies():
        sys.exit(1)

    try:
        start_server()
    except KeyboardInterrupt:
        print("\n\n服务已停止")
    except Exception as e:
        print(f"\n[错误] 启动失败: {e}")
        input("\n按 Enter 退出...")


if __name__ == "__main__":
    main()
