@echo off
echo ========================================
echo     123网盘后端启动
echo ========================================
echo.
echo [提示] 首次运行请先执行 123Pan_login.bat
echo [提示] 若网盘文件无法列出，请重新运行 123Pan_login.bat 更新token
echo.
echo ========================================
py pan123_main.py
pause