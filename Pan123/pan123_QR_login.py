import requests
import qrcode
import time
import json

class Pan123Scanner:
    def __init__(self):
        self.session = requests.Session()
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer": "https://www.123pan.com/",
            "platform": "web",
            "app-version": "4",
            "origin": "https://www.123pan.com",
            "Content-Type": "application/json"
        }

    def start(self):
        # 1. 获取二维码数据
        global token, final_token
        gen_url = "https://login.123pan.com/api/user/qr-code/generate"
        print("[*] 正在向服务器请求生成二维码...")
        res = self.session.get(gen_url, headers=self.headers).json()

        if res.get("code") != 0:
            print("[-] 获取失败:", res.get("message"))
            return None

        auth_url = res["data"]["url"]
        uni_id = res["data"]["uniID"]
        full_qr_text = f"{auth_url}?env=production&uniID={uni_id}&source=123pan&type=login"
        print("\n" + "=" * 50)
        print("请用微信扫描下方控制台的二维码")
        print("=" * 50 + "\n")

        qr = qrcode.QRCode()
        qr.add_data(full_qr_text)
        qr.print_ascii(invert=True)

        # 2. 轮询状态
        check_url = f"https://login.123pan.com/api/user/qr-code/result?uniID={uni_id}"

        while True:
            response = self.session.get(check_url, headers=self.headers)
            status_res = response.json()
            code = status_res.get("code")

            if code == 0:
                data = status_res.get("data", {})
                login_status = data.get("loginStatus")
                scan_platform = data.get("scanPlatform")

                if login_status == 0 and scan_platform == 0:
                    print("\r[*] 等待扫码中...                ", end="", flush=True)
                elif login_status == 1 and scan_platform == 4:
                    print("\r[*] 已扫码！请在手机上点击【确认】...   ", end="", flush=True)
                elif login_status == 4:
                    print("\n[-] 二维码已过期，请重新运行！")
                    break

                elif login_status != 0:
                    print(f"\n\n[+] 拦截到确认信号！准备执行换 Code 协议...")

                    try:
                        # 【第二步：换取 wechat_code】
                        wx_code_url = "https://login.123pan.com/api/user/qr-code/wx_code"
                        payload_wx = {"uniID": uni_id}
                        res_wx = self.session.post(wx_code_url, json=payload_wx, headers=self.headers).json()

                        wechat_code = res_wx.get("data", {}).get("wxCode")
                        if not wechat_code:
                            print(f"[-] 换取 wechat_code 失败，服务器返回: {res_wx}")
                            break
                        print(f"[*] 成功获取临时 wechat_code: {wechat_code[:10]}...")

                        # 【第三步：正式 sign_in 换取 Token】
                        sign_in_url = "https://login.123pan.com/api/user/sign_in"
                        payload_sign = {
                            "from": "web",
                            "type": 4,
                            "wechat_code": wechat_code
                        }
                        res_sign = self.session.post(sign_in_url, json=payload_sign, headers=self.headers).json()

                        # 提取最终 Token
                        token = res_sign.get("data", {}).get("token") or self.session.cookies.get("sso-token")

                        if token:
                            final_token = f"Bearer {token}"
                            login_headers = self.headers.copy()
                            login_headers['authorization'] = final_token

                            user_info_res = requests.get("https://www.123pan.com/b/api/user/info",headers=login_headers).json()
                            if user_info_res.get("code") == 0:
                                info_data = user_info_res.get("data",{})
                                nickname = info_data.get("Nickname","")
                                print(f"\n 欢迎您！" + nickname)

                                data = {
                                    "token": final_token
                                }
                                with open("pan123_token.json", "w", encoding="utf-8") as file:
                                    json.dump(data, file, ensure_ascii=False, indent=4)

                                return final_token
                            else:
                                print(f"\n[-] Token 获取成功，但验证失败: {user_info_res}")
                                return None
                        else:
                            print(f"[-] sign_in 成功但未找到 token，返回: {res_sign}")
                            break
                    except Exception as e:
                        print(f"[-] 后续握手发生异常: {e}")
                        break
        time.sleep(2)
        return None


if __name__ == "__main__":
    scanner = Pan123Scanner()
    scanner.start()