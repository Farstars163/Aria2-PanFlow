import requests
import uuid
from typing import Any, Dict
import urllib.parse
import base64
from requests import Response

class Pan123_lite:

    def __init__(self,token:str):
        self.session = requests.Session()
        self.token = token if token.startswith("Bearer") else f"Bearer {token}"
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer": "https://www.123pan.com/",
            "platform": "web",
            "app-version": "4",
            "authorization":self.token,
            "origin": "https://www.123pan.com",
            "LoginUuid": uuid.uuid4().hex,
            "Content-Type": "application/json"
        }
        self.file_list = []
    def dir_list(self,parent_id: int = 0,page: int = 1,limit: int = 100):
        url = "https://www.123pan.com/api/file/list/new"
        params = {
                 "driveId": 0,
                 "limit": limit,
                 "next": 0,
                 "orderBy": "file_id",
                 "orderDirection": "desc",
                 "parentFileId": str(parent_id),
                 "trashed": False,
                 "SearchData": "",
                 "Page": str(page),
                 "OnlyLookAbnormalFile": 0,
             }
        try:
            dir_res = requests.get(url,params=params,headers=self.headers).json()
            if dir_res.get("code") == 0:
                return dir_res["data"]["InfoList"]
            else:
                print(f"[-] 获取目录失败: {dir_res}")
                return []
        except Exception as e:
            print(f"[-] 请求目录发生异常: {e}")
            return []


    @staticmethod
    def make_result(code: int = 0, message: str = "ok", data: Any = None) -> Dict[str, Any]:
        return {"code": code, "message": message, "data": data}

    def get_download_url(self, index: int) -> Dict[str, Any]:
        if not (0 <= index < len(self.file_list)):
            return self.make_result(-1, "无效的文件编号")
        item = self.file_list[index]
        return self.get_item_download_url(item)

    def get_item_download_url(self, item: Dict) -> Response | Any:

        # 文件夹走批量下载接口，文件走单文件接口
        file_id = item.get("fileId") or item.get("FileId")
        file_type = item.get("type") if item.get("type") is not None else item.get("Type")
        file_name = item.get("fileName") or item.get("FileName")
        file_size = item.get("size") if item.get("size") is not None else item.get("Size")
        etag = item.get("etag") or item.get("Etag")
        s3_key_flag = item.get("s3KeyFlag") or item.get("S3KeyFlag") or item.get("s3keyFlag")

        if not file_id:
            return self.make_result(-1, f"无法提取文件 ID，原始数据: {item}")

        if file_type == 1:
            api_path = "https://www.123pan.com/a/api/file/batch_download_info"
            payload = {"fileIdList": [{"fileId": int(file_id)}]}
        else:
            api_path = "https://www.123pan.com/a/api/file/download_info"
            payload = {
                "driveId": 0,
                "etag": etag,
                "fileId": file_id,
                "s3keyFlag": s3_key_flag,
                "type": file_type,
                "fileName": file_name,
                "size": file_size,
            }
        download_res = requests.post(api_path,json=payload,headers=self.headers).json()
        if download_res["code"] != 0:
            return download_res

        res_data = download_res.get("data", {})
        download_url = res_data.get("DownloadUrl") or res_data.get("downloadUrl")

        if not download_url:
            return self.make_result(-1, f"未找到下载链接，服务器原始返回: {download_res}")
        try:
            # 1. 解析 URL 里的 params 参数
            parsed_url = urllib.parse.urlparse(download_url)
            qs = urllib.parse.parse_qs(parsed_url.query)

            if 'params' in qs:
                # 2. 拿到 base64 字符串并补全等号防报错
                b64_str = qs['params'][0]
                b64_str += "=" * ((4 - len(b64_str) % 4) % 4)

                # 3. 模拟 JS 的 atob 和 decodeURI
                dcode_bytes = base64.b64decode(b64_str)
                dcode_str = urllib.parse.unquote(dcode_bytes.decode('utf-8'))

                # 4. 判断 is_s3
                is_s3 = qs.get('is_s3', ['0'])[0]

                if is_s3 == '1':
                    # 按照 JS 逻辑，如果是 S3，直接返回 dcode_str
                    return self.make_result(0, "ok", {"url": dcode_str})
                else:
                    # 模拟 JS 的 XMLHttpRequest (XHR) 去摸一下
                    requests.packages.urllib3.disable_warnings()
                    xhr_headers = self.headers.copy()
                    xhr_headers["Accept"] = "*/*"  # 伪装成 XHR 请求

                    resp = self.session.get(dcode_str, headers=xhr_headers, allow_redirects=False, verify=False)

                    # 按照 JS 逻辑分支判断
                    if resp.status_code == 200 or resp.status_code == 302:
                        return self.make_result(0, "ok", {"url": dcode_str})
                    elif resp.status_code == 210:
                        res_json = resp.json()
                        if res_json.get("code") == 0:
                            return self.make_result(0, "ok", {"url": res_json["data"]["redirect_url"]})

        except Exception as e:
            print(f"[-] 跳板网页解密失败: {e}")
            pass  # 报错了就退回到返回原始 download_url

            # 如果没有 params 或者解密失败，直接给原链接（兜底）
        return self.make_result(0, "ok", {"url": download_url})

