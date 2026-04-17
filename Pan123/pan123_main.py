import json
import os
from flask import Flask, request, jsonify
from flask_cors import CORS
from pan123_lite import Pan123_lite
import requests
# ----------------- 配置与初始化 -----------------
TOKEN_FILE = "pan123_token.json"
app = Flask(__name__)
CORS(app)


def get_pan_api():
    """每次请求时动态获取最新的 API 实例"""
    if not os.path.exists(TOKEN_FILE):
        return None
    try:
        with open(TOKEN_FILE, "r", encoding="utf-8") as f:
            token = json.load(f).get("token")
            return Pan123_lite(token) if token else None
    except:
        return None


# ----------------- API 路由定义 -----------------

@app.route("/api/list", methods=["GET"])
def list_files():
    """获取指定目录下的文件列表"""
    pan_api = get_pan_api()
    if not pan_api:
        return jsonify({"code": 401, "message": "未找到授权 Token，请先在服务器端运行扫码脚本"})

    # 接收前端传来的 parent_id，默认是 0（根目录）
    parent_id = request.args.get("parent_id", 0, type=int)

    files = pan_api.dir_list(parent_id=parent_id, limit=100)

    # 如果 files 为空，可能目录为空，也可能 token 过期，这里统一返回给前端
    return jsonify({"code": 0, "data": files})


@app.route("/api/download", methods=["POST"])
def get_download():
    """提取文件直链"""
    pan_api = get_pan_api()
    if not pan_api:
        return jsonify({"code": 401, "message": "未授权"})

    # 接收前端传过来的整个文件信息字典
    file_info = request.json
    if not file_info:
        return jsonify({"code": -1, "message": "请求体为空"})

    filename = file_info.get('fileName') or file_info.get('FileName')
    save_dir = file_info.get('dir')
    link_res = pan_api.get_item_download_url(file_info)
    if link_res.get('code') != 0:
        return jsonify({"code":-1,"message":link_res.get("message","提取直链失败")})

    real_link = link_res["data"]["url"]

    aria2_payload = {
        "jsonrpc": "2.0",
        "id": "123pan",
        "method": "aria2.addUri",
        "params": [
            [real_link],
            {
                "out": filename,
                "header": [
                    "Referer: https://www.123pan.com/",
                    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ],
                "max-connection-per-server": "16",
                "split": "8",
            }
        ]
    }
    if save_dir:
        aria2_payload['params'][1]['dir'] = save_dir
    try:
        res = requests.post("http://localhost:6800/jsonrpc", json=aria2_payload)
        if "result" in res.json():
            return jsonify({"code":0,"message":"已成功推送到 Aria2！"})
        else:
            return jsonify({"code":-1,"message":"Aria2 拒绝了请求，请检查配置"})
    except Exception as e:
        return jsonify({'code': -1,"message":f"连接本地 Aria2 失败: {e}"})


if __name__ == "__main__":
    print("=" * 50)
    print("🚀 123云盘 Web API 引擎已启动")
    print("📡 监听地址: http://127.0.0.1:5001")
    print("⚠️  若 Token 过期，请在另外的终端运行扫码脚本更新 pan123_token.json")
    print("=" * 50)
    app.run(host='0.0.0.0', port=5001)