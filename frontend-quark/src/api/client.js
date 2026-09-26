import axios from 'axios'

export const http = axios.create({
  baseURL: '/api',
  timeout: 20000
})

// 响应拦截: 统一取出 Result.data; 非 200 code 抛出错误消息
http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 200) return body.data
      throw new Error(body.msg || '请求失败')
    }
    return body
  },
  (err) => {
    const msg = err.response?.data?.msg || err.message || '网络异常'
    throw new Error(msg)
  }
)

export function wsUrl() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${location.host}/ws/progress`
}

/* ---------------- API ---------------- */

export const tasksApi = {
  add: (payload) => http.post('/tasks/add', payload),
  addTorrent: (payload) => http.post('/tasks/torrent', payload),
  getPeers: (gid) => http.get('/tasks/' + gid + '/peers'),
  overview: () => http.get('/tasks/overview'),
  status: (gid) => http.get(`/tasks/${gid}`),
  pause: (gid) => http.post(`/tasks/${gid}/pause`),
  unpause: (gid) => http.post(`/tasks/${gid}/unpause`),
  remove: (gid) => http.post(`/tasks/${gid}/remove`),
  finish: (gid) => http.post(`/tasks/${gid}/finish`),
  clear: (gid) => http.post(`/tasks/${gid}/clear`)
}

export const fsApi = {
  list: (path) => http.get('/fs/list', { params: { path } })
}

export const baiduApi = {
  list: (token, dir) => http.get('/baidu', { params: { action: 'list', token, dir } }),
  download: (payload) => http.post('/baidu', payload),
  exchangeToken: (payload) => http.post('/baidu/oauth-token', payload),
  cdpToken: () => http.get('/baidu/cdp-token')
}

export const quarkApi = {
  list: (cookie, dir) => http.get('/quark', { params: { action: 'list', cookie, dir } }),
  download: (payload) => http.post('/quark', payload),
  cdpCookie: () => http.get('/quark/cdp-cookie')
}

export const ucApi = {
  list: (cookie, dir) => http.get('/uc', { params: { action: 'list', cookie, dir } }),
  download: (payload) => http.post('/uc', payload),
  cdpCookie: () => http.get('/uc/cdp-cookie')
}

export const mcloudApi = {
  list: (authorization, dir) => http.get('/mcloud', { params: { action: 'list', authorization, dir } }),
  download: (payload) => http.post('/mcloud', payload),
  cdpCookie: () => http.get('/mcloud/cdp-cookie')
}

export const xunleiApi = {
  list: (params) => http.get('/xunlei', { params: { action: 'list', ...params } }),
  download: (payload) => http.post('/xunlei', payload),
  cdpToken: () => http.get('/xunlei/cdp-token')
}

export const pan123Api = {
  list: (parentId) => http.get('/pan123/list', { params: { parentId } }),
  download: (payload) => http.post('/pan123/download', payload)
}

export const aliyunApi = {
  list: (dir) => http.get('/aliyun/list', { params: { dir } }),
  download: (payload) => http.post('/aliyun/download', payload)
}

/* ---------------- 网盘服务管理 ---------------- */

export const panServiceApi = {
  authLinks: () => http.get('/pan-service/auth-links'),
  statusAll: () => http.get('/pan-service/status'),
  status: (service) => http.get(`/pan-service/status/${service}`),
  start: (service) => http.post(`/pan-service/start/${service}`),
  stop: (service) => http.post(`/pan-service/stop/${service}`),
  stopAll: () => http.post('/pan-service/stop-all'),
  services: () => http.get('/pan-service/services'),
  qrcode: (service) => http.get(`/pan-service/qrcode/${service}`),
  // 123 云盘网页内扫码 (对接官方 API)
  pan123QrGenerate: () => http.post('/pan-service/pan123/qr-generate'),
  pan123QrStatus: (uniID) => http.get('/pan-service/pan123/qr-status', { params: { uniID } }),
  pan123QrConfirm: (uniID) => http.post(`/pan-service/pan123/qr-confirm?uniID=${uniID}`),
  pan123TokenStatus: () => http.get('/pan-service/pan123/token-status'),
  pan123TokenClear: () => http.post('/pan-service/pan123/token-clear')
}

/* ---------------- 下载目录(本地记忆) ---------------- */

const DIR_KEY = 'panflow.downloadDir'

export function getDownloadDir() {
  return localStorage.getItem(DIR_KEY) || ''
}

export function setDownloadDir(dir) {
  localStorage.setItem(DIR_KEY, dir)
}

/* ---------------- 代理设置(下载走代理, 加速国外链接) ---------------- */

export const settingsApi = {
  getProxy: () => http.get('/settings/proxy'),
  setProxy: (proxy) => http.post('/settings/proxy', { proxy }),
  testProxy: (proxy) => http.post('/settings/proxy/test', { proxy }),
  getGlobal: () => http.get('/settings/global'),
  setGlobal: (payload) => http.post('/settings/global', payload)
}

/* ---------------- 格式化工具 ---------------- */

export function formatSize(bytes) {
  if (!bytes || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return (bytes / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 2) + ' ' + units[i]
}

export function formatSpeed(bps) {
  if (!bps || bps <= 0) return '0 B/s'
  return formatSize(bps) + '/s'
}

export function taskName(task) {
  if (task.out) return task.out
  const files = task.files
  if (Array.isArray(files) && files.length > 0 && files[0]?.path) {
    const path = files[0].path
    return path.split(/[\\/]/).pop() || path
  }
  return task.gid || '未知任务'
}

export function taskProgress(task) {
  const total = Number(task.totalLength) || 0
  const done = Number(task.completedLength) || 0
  if (total <= 0) return 0
  return Math.min(100, Math.round((done / total) * 10000) / 100)
}

export const STATUS_META = {
  active: { label: '下载中', tone: 'active' },
  waiting: { label: '等待中', tone: 'waiting' },
  paused: { label: '已暂停', tone: 'paused' },
  complete: { label: '已完成', tone: 'complete' },
  error: { label: '出错', tone: 'error' },
  removed: { label: '已移除', tone: 'removed' },
  seed: { label: '做种中', tone: 'seed' }
}
