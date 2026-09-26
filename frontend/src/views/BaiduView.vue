<script setup>
import { ref, onMounted } from 'vue'
import { baiduApi, formatSize, getDownloadDir } from '@/api/client'
import { toast } from '@/api/toast'
import DirPickerModal from '@/components/DirPickerModal.vue'
import SealStamp from '@/components/SealStamp.vue'

const token = ref(localStorage.getItem('panflow.baidu.token') || '')
// 授权引导提示: 自动填充可用时引导点击「自动填充」; 失败时改回手动复制说明
const authTip = ref('点击授权跳转百度登录；授权成功后点击「自动填充」自动获取登录凭证。')
const dir = ref('/')
const files = ref([])
const loading = ref(false)
const pushing = ref(new Set())
const downloadDir = ref(getDownloadDir())
const showDirPicker = ref(false)
const folderSizes = ref({})
async function calcFolderSize(dirPath, depth = 0) {
  if (depth > 10) return 0
  const data = await baiduApi.list(token.value.trim(), dirPath)
  const items = (data && data.list) || []
  let total = 0
  for (const r of items) { if (r.isdir === 1) total += await calcFolderSize(r.path || dirPath + '/' + r.server_filename, depth + 1); else total += Number(r.size) || 0 }
  return total
}
async function fillFolderSizes(list) {
  for (const r of list) { if (r.isdir === 1) { folderSizes.value[r.fs_id] = '…'; try { folderSizes.value[r.fs_id] = formatSize(await calcFolderSize(r.path || '/' + r.server_filename)) } catch (e) { folderSizes.value[r.fs_id] = '' } } }
}

// 百度 OAuth 公共 client_id 池(源码内置)
const clientIds = [
  'L6g70tBRRIXLsY0Z3HwKqlRE', 'NqOMXF6XGhGRIGemsQ9nG0Na', 'fSds3K4w43rw37tOqlQmTa2kDwaczK4U',
  'TFwtw8uwHxpdkvVqVKdIlx1XqXUnr1zG', '9dgBV9yesuBVOXaxls7aVHbLBLqU8yyg', 'l9DdBOG4RYroMscmzK5OChdaGelgd92M',
  'Kyr013gHQBf2immy3fQt1jZ3nZVpiGAm', 'iYCeC9g08h5vuP9UqvPHKKSVrKFXGa1v', 'omiOnr2tYnN9vSyDErcVFWpPU2mZA7YO',
  'QHOuRXiepJBMjtk0esLhrPoNlQyYd0mF', 'IlLqBbU3GjQ0t46TRwFateTprHWl39zF'
]

/** 百度授权: 与源码一致, 用 <a rel="noreferrer"> 点击打开(抑制 Referer, 绕过 referer_mismatch) */
function authorize() {
  const cid = clientIds[Math.floor(Math.random() * clientIds.length)]
  const url = 'https://openapi.baidu.com/oauth/2.0/authorize?response_type=token'
    + '&scope=basic,netdisk&client_id=' + cid + '&redirect_uri=oob'
  const a = document.createElement('a')
  a.href = url
  a.target = '_blank'
  a.rel = 'noreferrer'   // 关键: 不发送 Referer, 百度不校验域名
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  toast.info('授权成功后，点击「自动填充」按钮自动获取')
}

/** 百度 CDP 自动填充: 扫描调试浏览器页面 URL 提取 access_token(免手动复制) */
async function autoFillToken() {
  try {
    const res = await baiduApi.cdpToken()
    if (typeof res === 'string' && res.length > 10) {
      token.value = res
      localStorage.setItem('panflow.baidu.token', res)
      toast.success('已自动获取 access_token 并回填')
      load()
    } else {
      toast.error('未找到 access_token，请先在调试浏览器中完成百度授权')
      authTip.value = '授权成功后，请复制地址栏 access_token= 后面的字符串，粘贴到下方。'
    }
  } catch (e) {
    toast.error('自动获取失败: ' + e.message)
    authTip.value = '授权成功后，请复制地址栏 access_token= 后面的字符串，粘贴到下方。'
  }
}

onMounted(() => { if (token.value) load() })

// 目录栈: 进入子目录/返回上级(与其他网盘一致)
const pathStack = ref([])

function enterDir(row) {
  if (row.isdir !== 1) return
  pathStack.value.push(dir.value)
  dir.value = row.path || (dir.value === '/' ? '/' + row.server_filename : dir.value + '/' + row.server_filename)
  load()
}

function goBack() {
  if (pathStack.value.length === 0) return
  dir.value = pathStack.value.pop()
  load()
}

async function load() {
  if (!token.value.trim()) { toast.error('请先填入 access_token'); return }
  loading.value = true
  try {
    const data = await baiduApi.list(token.value.trim(), dir.value)
    files.value = (data && data.list) || []
    fillFolderSizes(files.value)
    localStorage.setItem('panflow.baidu.token', token.value.trim())
  } catch (e) {
    toast.error(e.message)
    files.value = []
  } finally {
    loading.value = false
  }
}

async function download(row) {
  pushing.value.add(row.fs_id)
  try {
    await baiduApi.download({
      action: 'download',
      token: token.value.trim(),
      fs_id: row.fs_id,
      filename: row.server_filename,
      dir: (downloadDir.value ? downloadDir.value + (row.relDir || '') : (row.relDir || '') || undefined)
    })
    toast.success(`「${row.server_filename}」已推送`)
  } catch (e) {
    toast.error(e.message)
  } finally {
    pushing.value.delete(row.fs_id)
  }
}

async function collectFiles(dirPath, relDir, depth = 0) {
  const list = []
  if (depth > 6) return list
  const data = await baiduApi.list(token.value.trim(), dirPath)
  const items = (data && data.list) || []
  for (const r of items) {
    if (r.isdir === 0) list.push({ ...r, relDir })
    else if (r.isdir === 1) {
      const sub = await collectFiles(r.path || dirPath + '/' + r.server_filename, relDir + '/' + r.server_filename, depth + 1)
      list.push(...sub)
    }
  }
  return list
}

// 批量勾选推送
const checked = ref(new Set())
function toggleCheck(row) {
  if (checked.value.has(row.fs_id)) checked.value.delete(row.fs_id)
  else checked.value.add(row.fs_id)
}
async function pushAll() {
  const sel = files.value.filter((r) => checked.value.has(r.fs_id))
  if (sel.length === 0) { toast.error('未勾选文件'); return }
  const pending = []
  for (const r of sel) {
    if (r.isdir === 1) {
      const sub = await collectFiles(r.path, '/' + r.server_filename)
      pending.push(...sub)
    } else {
      pending.push(r)
    }
  }
  let ok = 0
  for (const f of pending) {
    try { await download(f); ok++ } catch (e) { /* 单个失败已提示 */ }
  }
  if (ok > 0) toast.success(`已推送 ${ok} 个文件到 Aria2`)
}

function pushBtnText() {
  const sel = files.value.filter((r) => checked.value.has(r.fs_id))
  if (sel.length > 0 && sel.every((r) => r.isdir === 1)) return '点我推送文件夹到下载列表'
  if (sel.length > 0) return '点我推送文件到下载列表'
  return '推送勾选文件到 Aria2'
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <div class="page-title-sub font-calligraphy-行">百度网盘 · 直链摆渡</div>
        <h1 class="page-title">百度云盘</h1>
      </div>
      <div class="vertical-note">网中取物 · 如掬清泉</div>
      <div class="head-line"></div>
      <SealStamp :text="'度'" :size="58" />
    </header>

    <div class="credential-card">
      <div class="field" style="margin-bottom: 12px;">
        <label class="field-label">百度网盘授权</label>
        <div class="field-row">
          <button class="ink-btn" @click="authorize">授权登录 ↗</button>
          <span class="tip">{{ authTip }}</span>
        </div>
      </div>
      <div class="field" style="margin-bottom: 12px;">
        <label class="field-label">access_token</label>
        <div class="field-row">
          <input v-model="token" class="ink-input" type="password" placeholder="百度网盘 access_token" @keyup.enter="load" />
          <button class="ink-btn ghost" @click="autoFillToken">自动填充</button>
        </div>
      </div>
      <div class="field-row">
        <button v-if="pathStack.length > 0" class="ink-btn ghost" @click="goBack">返回上级 ←</button>
        <input v-model="dir" class="ink-input" style="max-width: 260px;" placeholder="目录，默认 /" />
        <button class="ink-btn seal" :disabled="loading" @click="load">{{ loading ? '取物中…' : '获取列表' }}</button>
        <button class="ink-btn ghost" @click="pushAll">{{ pushBtnText() }}</button>
        <button class="ink-btn ghost" @click="showDirPicker = true">
          下载目录: {{ downloadDir || 'aria2 默认' }}
        </button>
      </div>
    </div>

    <div class="ink-table-wrap">
      <table class="ink-table">
        <thead>
          <tr><th style="width: 36px;"></th><th style="width: 46px;">形</th><th>名</th><th style="width: 130px;">大小</th><th style="width: 110px;">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in files" :key="row.fs_id" @dblclick="row.isdir === 1 ? enterDir(row) : (row.isdir === 0 ? download(row) : null)">
            <td>
              <input
                type="checkbox"
                :checked="checked.has(row.fs_id)"
                @change="toggleCheck(row)"
                style="accent-color: var(--jade); width: 15px; height: 15px; cursor: pointer;"
              />
            </td>
            <td><span :class="row.isdir === 1 ? 'folder-mark' : 'file-mark'">{{ row.isdir === 1 ? '▣' : '▤' }}</span></td>
            <td class="fname font-body-楷">{{ row.server_filename }}</td>
            <td>{{ row.isdir === 1 ? (folderSizes[row.fs_id] ?? '…') : formatSize(row.size) }}</td>
            <td>
              <button
                v-if="row.isdir === 0"
                class="ink-btn ghost small"
                :disabled="pushing.has(row.fs_id)"
                @click.stop="download(row)"
              >{{ pushing.has(row.fs_id) ? '推送中' : '下载' }}</button>
              <button v-else class="ink-btn ghost small" @click.stop="enterDir(row)">进入</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="files.length === 0 && !loading" class="empty-ink">
        <div class="char">虚</div>
        <div class="hint">未取到文件 · 请填入有效 token 后点「获取列表」</div>
      </div>
    </div>

    <DirPickerModal v-model="showDirPicker" @select="(p) => (downloadDir = p)" />
  </div>
</template>
