@echo off
cd /d "%~dp0"

echo ========================================
echo   停止运行 Aria2-PanFlow 服务
echo ========================================
echo.

echo 正在强制停止所有相关进程...

:: 1. 停止 Aria2
echo [停止] Aria2...
taskkill /F /IM aria2c.exe /T 2>nul
if errorlevel 1 (echo   - 未运行) else (echo   - 已停止)

:: 2. 停止 Python 服务
echo [停止] 阿里云盘服务...
taskkill /F /IM aliyun_service.exe /T 2>nul
if errorlevel 1 (echo   - 未运行) else (echo   - 已停止)

echo [停止] 123盘服务...
taskkill /F /IM pan123_service.exe /T 2>nul
taskkill /F /IM pan123_login.exe /T 2>nul
if errorlevel 1 (echo   - 未运行) else (echo   - 已停止)

:: 3. 停止 Java 后端
echo [停止] 后端服务...
taskkill /F /IM java.exe /T 2>nul
if errorlevel 1 (echo   - 未运行) else (echo   - 已停止)

echo.
echo ========================================
echo 所有服务已尝试停止，请检查任务管理器
echo ========================================
pause