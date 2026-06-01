@echo off
chcp 65001 >nul

py -3 launcher.py 2>nul
if %ERRORLEVEL% neq 9009 exit /b %ERRORLEVEL%

python launcher.py 2>nul
if %ERRORLEVEL% neq 9009 exit /b %ERRORLEVEL%

echo [ERROR] Python not found.
echo Please install Python 3.8+ from https://www.python.org/downloads/
echo Make sure to check "Add Python to PATH" during installation.
pause
