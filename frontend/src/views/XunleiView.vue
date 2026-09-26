<script setup>
import { ref, onMounted } from 'vue'
import { xunleiApi, formatSize, getDownloadDir } from '@/api/client'
import { toast } from '@/api/toast'
import DirPickerModal from '@/components/DirPickerModal.vue'
import SealStamp from '@/components/SealStamp.vue'

const TOKEN_KEY = 'panflow.xunlei.token'

// 令牌: { accessToken, tokenType, captchaToken, deviceId }, 存于本机 localStorage
function loadToken() {
  try {
    return JSON.parse(localStorage.getItem(TOKEN_KEY) || '{}')
  } catch {
    return {}
  }
}
function saveToken(t) {
  localStorage.setItem(TOKEN_KEY, JSON.stringify(t))
}

const token = ref(loadToken())
// 授权引导提示: 自动获取可用时引导点击「自动获取」; 失败时改回手动填写说明
const authTip = ref('点击授权打开迅雷云盘登录页；登录成功后点击「自动获取」自动抓取令牌。')
const dir = ref('')
const files = ref([])
const loading = ref(false)
const pushing = ref(new Set())
const downloadDir = ref(getDownloadDir())
const showDirPicker = ref(false)

// 目录栈: 用于双击文件夹进入 (迅雷根目录 parent_id 为空字符串)
const pathStack = ref([''])

onMounted(() => { if (token.value.accessToken) load() })

/** 授权跳转: 打开迅雷云盘登录页(即授权页), 在调试浏览器中登录后即可自动获取 */
function authorize() {
  window.open('https://pan.xunlei.com/', '_blank')
  toast.info('登录成功后，点击「自动获取」按钮自动抓取令牌')
}

/** 自动获取令牌: 后端通过浏览器 CDP(调试端口 9222) 读取 pan.xunlei.com 页面的 localStorage */
async function autoFetchToken() {
  try {
    const res = await xunleiApi.cdpToken()
    if (res && res.accessToken) {
      token.value = res
      saveToken(res)
      toast.success('已自动获取令牌并回填')
      load()
    } else {
      toast.error('未读取到有效令牌，请先在调试浏览器中完成迅雷云盘登录')
      authTip.value = '自动获取失败，请手动填写 access_token（可从浏览器开发者工具 localStorage 的 credentials_* 中查看）。'
    }
  } catch (e) {
    toast.error('自动获取失败: ' + e.message)
    authTip.value = '自动获取失败，请手动填写 access_token（可从浏览器开发者工具 localStorage 的 credentials_* 中查看）。'
  }
}

function tokenParams() {
  return {
    accessToken: (token.value.accessToken || '').trim(),
    tokenType: (token.value.tokenType || 'Bearer').trim(),
    captchaToken: (token.value.captchaToken || '').trim(),
    deviceId: (token.value.deviceId || '').trim()
  }
}

async function load() {
  if (!tokenParams().accessToken) { toast.error('请先填入 access_token'); return }
  loading.value = true
  try {
    const data = await xunleiApi.list({ ...tokenParams(), dir: dir.value })
    // 后端返回迅雷原始结构: {files:[...], next_page_token}
    files.value = (data && data.files) || []
    fillFolderSizes(files.value)
    saveToken(token.value)
  } catch (e) {
    toast.error(e.message)
    files.value = []
  } finally {
    loading.value = false
  }
}

function enterDir(row) {
  pathStack.value.push(row.id)
  dir.value = row.id
  load()
}

function goBack() {
  if (pathStack.value.length <= 1) return
  pathStack.value.pop()
  dir.value = pathStack.value[pathStack.value.length - 1]
  load()
}

// 批量勾选推送
const checked = ref(new Set())
function isFolder(row) {
  return row.kind === 'drive#folder'
}

const folderSizes = ref({})
async function calcFolderSize(dirId, depth = 0) {
  if (depth > 10) return 0
  const data = await xunleiApi.list({ ...tokenParams(), dir: dirId })
  const items = (data && data.files) || []
  let total = 0
  for (const r of items) { if (r.kind === 'drive#folder') total += await calcFolderSize(r.id, depth + 1); else total += Number(r.size) || 0 }
  return total
}
async function fillFolderSizes(list) {
  for (const r of list) { if (r.kind === 'drive#folder') { folderSizes.value[r.id] = '…'; try { folderSizes.value[r.id] = formatSize(await calcFolderSize(r.id)) } catch (e) { folderSizes.value[r.id] = '' } } }
}
function toggleCheck(row) {
  if (checked.value.has(row.id)) checked.value.delete(row.id)
  else checked.value.add(row.id)
}
async function pushAll() {
  const list = files.value.filter((r) => checked.value.has(r.id))
  if (list.length === 0) { toast.error('未勾选文件'); return }
  let ok = 0
  for (const r of list) {
    if (isFolder(r)) {
      try {
        const all = await collectFiles(r.id, '/' + r.name)
        for (const f of all) { try { await download(f); ok++ } catch (e) {} }
      } catch (e) { /* 单个失败已提示 */ }
    } else {
      try { await download(r); ok++ } catch (e) { /* 单个失败已提示 */ }
    }
  }
  if (ok > 0) toast.success(`已推送 ${ok} 个文件到 Aria2`)
}
function pushBtnText() {
  const sel = files.value.filter((r) => checked.value.has(r.id))
  if (sel.length > 0 && sel.every((r) => isFolder(r))) return '点我推送文件夹到下载列表'
  if (sel.length > 0) return '点我推送文件到下载列表'
  return '推送勾选文件到 Aria2'
}

async function download(row) {
  pushing.value.add(row.id)
  try {
    await xunleiApi.download({
      action: 'download',
      fileId: row.id,
      filename: row.name,
      ...tokenParams(),
      dir: (downloadDir.value ? downloadDir.value + (row.relDir || '') : (row.relDir || '')) || undefined
    })
    toast.success(`「${row.name}」已推送`)
  } catch (e) {
    toast.error(e.message)
  } finally {
    pushing.value.delete(row.id)
  }
}
async function collectFiles(dirId, relDir, depth = 0) {
  const list = []
  if (depth > 6) return list
  const data = await xunleiApi.list({ ...tokenParams(), dir: dirId })
  const items = (data && data.files) || []
  for (const r of items) {
    if (r.kind === 'drive#folder') {
      const sub = await collectFiles(r.id, relDir + '/' + r.name, depth + 1)
      list.push(...sub)
    } else {
      list.push({ ...r, relDir })
    }
  }
  return list
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <div class="page-title-sub font-calligraphy-行">迅雷云盘 · 电光石火</div>
        <h1 class="page-title">迅雷云盘</h1>
      </div>
      <div class="vertical-note">雷声千嶂落 · 雨色万峰来</div>
      <div class="head-line"></div>
      <SealStamp :text="'迅'" :size="58" />
    </header>

    <div class="credential-card">
      <div class="field" style="margin-bottom: 12px;">
        <label class="field-label">迅雷云盘授权</label>
        <div class="field-row">
          <button class="ink-btn" @click="authorize">授权登录 ↗</button>
          <span class="tip">{{ authTip }}</span>
        </div>
      </div>
      <div class="field" style="margin-bottom: 12px;">
        <label class="field-label">access_token</label>
        <div class="field-row">
          <input v-model="token.accessToken" class="ink-input" type="password" placeholder="迅雷云盘 access_token（隐藏显示，自动获取后回填）" @keyup.enter="load" />
          <button class="ink-btn ghost" @click="autoFetchToken">自动获取</button>
        </div>
      </div>
      <details class="field-extra" style="margin-bottom: 12px;">
        <summary>高级选项（可选：令牌类型 / 设备 ID / 验证令牌）</summary>
        <div class="field-row" style="gap: 12px; margin-top: 10px;">
          <div class="field" style="flex: 1;">
            <label class="field-label">令牌类型</label>
            <input v-model="token.tokenType" class="ink-input" placeholder="Bearer" />
          </div>
          <div class="field" style="flex: 1;">
            <label class="field-label">设备 ID</label>
            <input v-model="token.deviceId" class="ink-input" placeholder="32 位十六进制（自动获取后回填）" />
          </div>
          <div class="field" style="flex: 1;">
            <label class="field-label">验证令牌</label>
            <input v-model="token.captchaToken" class="ink-input" placeholder="Captcha Token（自动获取后回填）" />
          </div>
        </div>
      </details>
      <div class="field-row">
        <button class="ink-btn seal" :disabled="loading" @click="load">{{ loading ? '取物中…' : '获取列表' }}</button>
        <button v-if="pathStack.length > 1" class="ink-btn ghost" @click="goBack">返回上级</button>
        <button class="ink-btn ghost" @click="showDirPicker = true">
          下载目录: {{ downloadDir || 'aria2 默认' }}
        </button>
        <button class="ink-btn ghost" @click="pushAll">{{ pushBtnText() }}</button>
      </div>
    </div>

    <div class="ink-table-wrap">
      <table class="ink-table">
        <thead>
          <tr><th style="width: 36px;"></th><th style="width: 46px;">形</th><th>名</th><th style="width: 130px;">大小</th><th style="width: 110px;">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in files" :key="row.id" @dblclick="isFolder(row) ? enterDir(row) : download(row)">
            <td>
              <input
                type="checkbox"
                :checked="checked.has(row.id)"
                @change="toggleCheck(row)"
                style="accent-color: var(--jade); width: 15px; height: 15px; cursor: pointer;"
              />
            </td>
            <td><span :class="isFolder(row) ? 'folder-mark' : 'file-mark'">{{ isFolder(row) ? '▣' : '▤' }}</span></td>
            <td class="fname font-body-楷">{{ row.name }}</td>
            <td>{{ isFolder(row) ? (folderSizes[row.id] ?? '…') : formatSize(row.size) }}</td>
            <td>
              <button
                v-if="!isFolder(row)"
                class="ink-btn ghost small"
                :disabled="pushing.has(row.id)"
                @click.stop="download(row)"
              >{{ pushing.has(row.id) ? '推送中' : '下载' }}</button>
              <button v-else class="ink-btn ghost small" @click.stop="enterDir(row)">进入</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="files.length === 0 && !loading" class="empty-ink">
        <div class="char">空</div>
        <div class="hint">未取到文件 · 请填入有效令牌后点「获取列表」</div>
      </div>
    </div>

    <DirPickerModal v-model="showDirPicker" @select="(p) => (downloadDir = p)" />
  </div>
</template>
