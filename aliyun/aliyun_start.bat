@echo off

echo 检查并安装依赖...

REM 尝试导入 aligo，失败则安装
python -c "import aligo" >nul 2>&1
if errorlevel 1 (
    echo 正在安装 aligo...
    pip install -U aligo
    pip install git+https://github.com/foyoux/aligo.git
) else (
    echo aligo 已安装，跳过
)

echo 阿里云网盘后端启动......
python aliyun.py
pause