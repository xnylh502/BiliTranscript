# B站视频文案提取器 - 一键启动脚本
# 右键选择"使用 PowerShell 运行"

$Host.UI.RawUI.WindowTitle = "B站文案提取器"

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  B站视频文案提取器" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# 检查Python
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    Write-Host "[错误] 未检测到 Python，请先安装 Python 3.8+" -ForegroundColor Red
    Write-Host "下载地址: https://www.python.org/downloads/" -ForegroundColor Yellow
    Read-Host "按 Enter 退出"
    exit 1
}

Write-Host "[1/3] 正在检查依赖..." -ForegroundColor Green
$pipResult = python -m pip install -q -r requirements.txt 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[提示] 依赖安装出现问题，尝试重新安装..." -ForegroundColor Yellow
    python -m pip install -r requirements.txt
}

Write-Host "[2/3] 正在启动服务..." -ForegroundColor Green
Write-Host "[3/3] 浏览器即将自动打开..." -ForegroundColor Green
Write-Host ""
Write-Host "服务地址: http://127.0.0.1:5000" -ForegroundColor Cyan
Write-Host "按 Ctrl+C 停止服务" -ForegroundColor Gray
Write-Host ""

# 启动服务
python app.py

Read-Host "按 Enter 退出"
