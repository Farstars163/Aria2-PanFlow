#!/bin/bash
# 墨舟 Aria2-PanFlow 独立版启动脚本 (macOS)
# 内置 JDK, 无需系统 Java; 自动适配 Apple Silicon / Intel
# 双击运行: 启动 aria2 + 后端 + 打开浏览器, 关闭浏览器后自动停止全部服务
cd "$(dirname "$0")" || exit 1

PORT=18080
ARIA2_PORT=16800

ARCH=$(uname -m)
echo "========================================"
echo "  墨舟 Aria2-PanFlow 独立版"
echo "  架构: $ARCH (内置 JDK, 无需系统 Java)"
echo "========================================"

# 选择对应架构的 JDK 与 aria2
if [ "$ARCH" = "arm64" ]; then
    JAVA_EXE="$PWD/jre/macos-arm64/Contents/Home/bin/java"
    ARIA2_DIR="$PWD/aria2/macos-arm64"
else
    JAVA_EXE="$PWD/jre/macos-x64/Contents/Home/bin/java"
    ARIA2_DIR="$PWD/aria2/macos-x64"
fi

if [ ! -f "$JAVA_EXE" ]; then
    echo "[错误] 未找到当前架构($ARCH)的内置 JDK"
    read -r -p "按回车退出..."
    exit 1
fi

# 0. 端口占用检查: 本应用端口均为非熟知端口, 若被占用即为上次残留的旧进程,
#    先杀掉占用端口的进程再全新启动, 避免多套旧服务并存互相干扰
for P in $PORT $ARIA2_PORT; do
    OLD_PIDS=$(lsof -ti tcp:$P 2>/dev/null)
    if [ -n "$OLD_PIDS" ]; then
        echo "[清理] 端口 $P 被旧进程占用 (PID: $OLD_PIDS), 正在终止..."
        kill -9 $OLD_PIDS 2>/dev/null
        sleep 1
    fi
done

# 1. 启动 Aria2 (内置对应架构二进制)
if [ -f "$ARIA2_DIR/aria2c" ]; then
    chmod +x "$ARIA2_DIR/aria2c" 2>/dev/null
    (cd "$ARIA2_DIR" && HTTP_PROXY= HTTPS_PROXY= ALL_PROXY= nohup ./aria2c --conf-path=aria2.conf >/dev/null 2>&1 &)
    echo "[OK] Aria2 ($ARCH) 已后台启动"
elif command -v aria2c >/dev/null 2>&1; then
    (cd "$PWD" && HTTP_PROXY= HTTPS_PROXY= ALL_PROXY= nohup aria2c --conf-path=aria2.conf >/dev/null 2>&1 &)
    echo "[OK] Aria2 (系统已安装) 已后台启动"
else
    echo "[!] 未找到内置 aria2, 可执行: brew install aria2"
fi

# 2. 启动 Java 后端 (内置 JDK)
(cd "$PWD" && nohup "$JAVA_EXE" -jar app/Aria2-PanFlow.jar >/dev/null 2>&1 &)
echo "[OK] 墨舟后端已后台启动"

# 3. 等待后端就绪后打开浏览器 (macOS 用自带浏览器 open 打开; 无 CDP 调试端口,
#    网盘凭据(如夸克 Cookie/迅雷令牌)需在页面手动填写)
for i in $(seq 1 30); do
    if nc -z 127.0.0.1 $PORT 2>/dev/null; then break; fi
    sleep 1
done
echo "[OK] 正在打开前端控制面板..."
open "http://localhost:$PORT/"

# 4. 驻留监控: 页面关闭后自动停止全部服务 (与 Windows exe 相同行为)
echo "[监控] 请勿关闭本窗口; 关闭浏览器页面后本脚本会自动停止服务并退出"
echo "[监控] 如需立即停止: 按 Ctrl+C"
echo ""
CLOSED_COUNT=0
while true; do
    sleep 3
    STATUS=$(curl -s -m 3 "http://localhost:$PORT/api/lifecycle/status" 2>/dev/null)
    if echo "$STATUS" | grep -q '"closed":true'; then
        CLOSED_COUNT=$((CLOSED_COUNT + 1))
        if [ "$CLOSED_COUNT" -ge 4 ]; then
            echo "[OK] 浏览器页面已关闭, 正在停止服务..."
            break
        fi
    else
        CLOSED_COUNT=0
    fi
done

# 停止全部服务
pkill -f "Aria2-PanFlow.jar" 2>/dev/null
pkill aria2c 2>/dev/null
pkill -f "aliyun_service" 2>/dev/null
echo "[OK] 服务已全部停止, 本窗口将自动关闭"
sleep 2
exit 0
