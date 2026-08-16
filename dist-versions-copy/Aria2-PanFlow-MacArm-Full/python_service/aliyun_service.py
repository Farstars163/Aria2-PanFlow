import os
import sys
import tempfile
import threading
from pathlib import Path


def get_current_dir():
    if getattr(sys, 'frozen', False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent


config_path = str(get_current_dir())
original_user_profile = os.environ.get('USERPROFILE')
os.environ['USERPROFILE'] = config_path
os.environ['HOME'] = config_path
os.environ['ALIGO_CONFIG_PATH'] = config_path

from aligo import Aligo
from flask import Flask, request, jsonify
from flask_cors import CORS
import requests
import qrcode

app = Flask(__name__)
CORS(app)

# =============================================================
# 自定义二维码显示: 只保存 PNG 到 TEMP 供网页轮询, 不再调用 qr_img.show()
# (aligo 默认 _show_qrcode_in_window 会调 qr_img.show() 弹出系统图片查看器)
# =============================================================
def show_qr_only(qr_link: str) -> str:
    try:
        qr_img = qrcode.make(qr_link)
        png_path = tempfile.mktemp('.png')
        qr_img.save(png_path)
        print(f"[*] 二维码已生成(仅网页显示): {png_path}")
        return png_path
    except Exception as e:
        print(f"[-] 生成二维码图片失败: {e}")
        return None


ali = None
try:
    print(f"--- 启动环境重定向: {config_path} ---")
    ali = Aligo(name="aliyun_aria2", show=show_qr_only)
    print("正在初始化 Aligo...")
except Exception as e:
    print(f"[-] Aligo 初始化失败: {e}")
    sys.exit(1)

if original_user_profile:
    os.environ['USERPROFILE'] = original_user_profile

# =============================================================

global_drive_id = None
try:
    print("正在扫描有数据的云盘...")
    drives = ali.list_my_drives()
    if drives:
        target_drive = max(drives, key=lambda d: d.used_size)
        global_drive_id = target_drive.drive_id
        print(f"[*] 成功锁定目标盘 ID: {global_drive_id} (已用空间: {target_drive.used_size} 字节)")
    else:
        global_drive_id = ali.get_default_drive().drive_id
except Exception as e:
    print(f"[-] 锁定盘符失败: {e}")



# =============================================================

@app.route('/api/aliyun', methods=['GET', 'POST'])
def aliyun_api():
    action = request.args.get('action') or (request.json and request.json.get('action'))

    if action == 'list':
        #default值 root
        dir_id = request.args.get('dir', 'root')
        if not dir_id or dir_id in ['undefined', 'null', '/', '']:
            dir_id = 'root'

        try:
            if not global_drive_id:
                return jsonify({'code': 500, 'msg': '云盘未初始化，请检查配置或重新登录'}), 500
            files = ali.get_file_list(parent_file_id=dir_id, drive_id=global_drive_id)
            items = []
            if files:
                for f in files:
                    items.append({
                        'file_id': f.file_id,
                        'name': f.name,
                        'type': f.type
                    })
            return jsonify({'items': items})
        except Exception as e:
            print(f"[-] 目录加载报错: {e}")
            return jsonify({'code': 500, 'msg': str(e)}), 500

    elif action == 'download':
        data = request.json
        file_id = data.get('file_id')
        filename = data.get('filename')
        save_dir = data.get('dir')

        try:
            download_info = ali.get_download_url(file_id=file_id, drive_id=global_drive_id)
            if not download_info or not download_info.url:
                return jsonify({'success': False, 'msg': '无法获取下载链接'})

            real_url = download_info.url

            aria2_payload = {
                "jsonrpc": "2.0",
                "id": "aligo-python",
                "method": "aria2.addUri",
                "params": [
                    [real_url],
                    {
                        "out": filename,
                        "header": [
                            "Referer: https://www.aliyundrive.com/",
                            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0"
                        ]
                    }
                ]
            }
            if save_dir:
                aria2_payload['params'][1]['dir'] = save_dir

            res = requests.post("http://localhost:6800/jsonrpc", json=aria2_payload,timeout=10)
            return jsonify({'success': 'result' in res.json()})

        except Exception as e:
            return jsonify({'success': False, 'msg': str(e)})


if __name__ == '__main__':
    print(" 阿里云盘启动！运行在 http://127.0.0.1:15001")
    app.run(host='127.0.0.1', port=15001)
