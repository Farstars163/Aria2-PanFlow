@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ========================================
echo   Aria2-PanFlow 启动脚本
echo ========================================

set "PYTHON_DIR=%~dp0python_service"
set "TOKEN_123=!PYTHON_DIR!\pan123_token.json"

:: 用户选择启动项
set /p "RUN_ALI=是否启动阿里云盘服务? (Y/N, 默认N): "
set /p "RUN_123=是否启动123盘服务? (Y/N, 默认N): "

:: 检查并启动 123 盘 Token 登录
if /i "!RUN_123!"=="Y" (
    if not exist "!TOKEN_123!" (
        echo [!] 123盘 Token 缺失, 正在启动登录...
        start /wait "" "!PYTHON_DIR!\pan123_login.exe"
    )
)

echo.
echo 正在启动各项服务...

:: 创建用于隐藏窗口的 VBS 脚本
set "VBS_FILE=%temp%\hidden_run_v5.vbs"
(
echo Set objShell = WScript.CreateObject("WScript.Shell"^)
echo If WScript.Arguments.Count ^>= 2 Then
echo   objShell.CurrentDirectory = WScript.Arguments(0^)
echo   objShell.Run WScript.Arguments(1^), 0, False
echo End If
) > "%VBS_FILE%"

:: 1. 启动阿里云盘服务
if /i "!RUN_ALI!"=="Y" (
    if exist "!PYTHON_DIR!\aliyun_service.exe" (
        cscript //nologo "%VBS_FILE%" "!PYTHON_DIR!" "aliyun_service.exe"
        echo [OK] 阿里云盘服务后台启动中...
    )
)

:: 2. 启动 Aria2
if exist "aria2\aria2c.exe" (
    cscript //nologo "%VBS_FILE%" "%~dp0aria2" "aria2c.exe --conf-path=aria2.conf"
    echo [OK] Aria2 后台启动中...
)

:: 3. 启动 123 盘服务
if /i "!RUN_123!"=="Y" (
    if exist "!TOKEN_123!" (
        cscript //nologo "%VBS_FILE%" "!PYTHON_DIR!" "pan123_service.exe"
        echo [OK] 123盘服务后台启动中...
    )
)

:: 4. 启动 Java 后端 (原 Aria2-PanFlow.exe)
if exist "app\Aria2-PanFlow.jar" (
    cscript //nologo "%VBS_FILE%" "%~dp0" "java -jar app\Aria2-PanFlow.jar"
    echo [OK] Java 后端服务后台启动中...
)

:: 自动打开前端页面
echo [OK] 正在自动打开前端控制面板...
start http://localhost:8080/index.html

:: 删除临时 VBS
del "%VBS_FILE%"

echo.
echo ========================================
echo          启动完成 (等待5秒同步状态)
echo ========================================
timeout /t 5 >nul

:: 状态检查
tasklist /FI "IMAGENAME eq aria2c.exe" 2>nul | find /I "aria2c.exe" >nul
if %errorlevel% equ 0 (echo   [运行中] Aria2) else (echo   [未运行] Aria2)

if /i "!RUN_ALI!"=="Y" (
    tasklist /FI "IMAGENAME eq aliyun_service.exe" 2>nul | find /I "aliyun_service.exe" >nul
    if %errorlevel% equ 0 (echo   [运行中] 阿里云盘服务) else (echo   [未运行/已停止] 阿里云盘服务)
)

tasklist /FI "IMAGENAME eq java.exe" 2>nul | find /I "java.exe" >nul
if %errorlevel% equ 0 (echo   [运行中] Java 后端) else (echo   [未运行] Java 后端)

echo ========================================

pause