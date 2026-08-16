#!/bin/bash
# 墨舟 Aria2-PanFlow 独立版停止脚本 (macOS) - 按端口关闭
cd "$(dirname "$0")" || exit 1

echo "========================================"
echo "  正在关闭 Aria2-PanFlow 全部进程 (按端口)"
echo "========================================"
echo ""

# 按端口关闭: 18080 后端 / 16800 Aria2 / 9222 调试浏览器 / 15000-15001 网盘服务
for P in 18080 16800 9222 15000 15001; do
    PIDS=$(lsof -ti tcp:$P 2>/dev/null)
    if [ -n "$PIDS" ]; then
        echo "  [已关闭] 端口 $P 的进程 (PID: $PIDS)"
        kill -9 $PIDS 2>/dev/null
    fi
done

# 关闭驻留的启动脚本进程与 Aria2 下载器
pkill -f "start.command" 2>/dev/null
pkill aria2c 2>/dev/null

echo ""
echo "[OK] 全部进程已关闭"
sleep 2
exit 0
