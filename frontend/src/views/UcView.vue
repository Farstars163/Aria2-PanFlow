<script setup>
import { ref, onMounted } from 'vue'
import { ucApi, formatSize, getDownloadDir } from '@/api/client'
import { toast } from '@/api/toast'
import DirPickerModal from '@/components/DirPickerModal.vue'
import SealStamp from '@/components/SealStamp.vue'

const cookie = ref(localStorage.getItem('panflow.uc.cookie') || '')
// 授权引导提示: 自动获取可用时引导点击「自动获取」; 失败时改回手动复制说明
const authTip = ref('点击授权打开 UC网盘登录页；登录成功后点击「自动获取」自动抓取 Cookie。')
const dir = ref('0')
const files = ref([])
const loading = ref(false)
const pushing = ref(new Set())
const downloadDir = ref(getDownloadDir())
const showDirPicker = ref(false)
const folderSizes = ref({})
async function calcFolderSize(dirId, depth = 0) {
  if (depth > 10) return 0
  const data = await ucApi.list(cookie.value.trim(), dirId)
  const items = (data && data.data && data.data.list) || []
  let total = 0
  for (const r of items) { if (r.dir) total += await calcFolderSize(r.fid || r.fids, depth + 1); else total += Number(r.size) || 0 }
  return total
}
async function fillFolderSizes(list) {
  for (const r of list) { if (r.dir) { const id = r.fid || r.fids; folderSizes.value[id] = '…'; try { folderSizes.value[id] = formatSize(await calcFolderSize(id)) } catch (e) { folderSizes.value[id] = '' } } }
}

// 目录栈: 用于双击文件夹进入
const pathStack = ref(['0'])

onMounted(() => { if (cookie.value) load() })

/** 授权跳转: 打开 UC网盘登录页(即授权页), 在调试浏览器中登录后即可自动获取 */
function authorize() {
  window.open('https://drive.uc.cn/', '_blank')
  toast.info('登录成功后，点击「自动获取」按钮自动抓取 Cookie')
}

/** 自动获取 UC网盘 cookie: 后端通过浏览器 CDP(调试端口 9222) 读取完整 cookie(含 HttpOnly) */
async function autoFetchCookie() {
  try {
    const res = await ucApi.cdpCookie()
    if (typeof res === 'string' && res.length > 10) {
      cookie.value = res
      localStorage.setItem('panflow.uc.cookie', res)
      toast.success('已自动获取 Cookie 并回填')
      load()
    } else {
      toast.error('未读取到有效 Cookie，请先在调试浏览器中完成 UC网盘登录')
      authTip.value = '自动获取失败，请复制浏览器 Cookie 手动粘贴到下方输入框。'
    }
  } catch (e) {
    toast.error('自动获取失败: ' + e.message)
    authTip.value = '自动获取失败，请复制浏览器 Cookie 手动粘贴到下方输入框。'
  }
}

async function load() {
  if (!cookie.value.trim()) { toast.error('请先填入 Cookie'); return }
  loading.value = true
  try {
    const data = await ucApi.list(cookie.value.trim(), dir.value)
    // 后端返回 UC 原始结构: {status, message, data:{list:[...]}}
    files.value = (data && data.data && data.data.list) || []
    fillFolderSizes(files.value)
    localStorage.setItem('panflow.uc.cookie', cookie.value.trim())
  } catch (e) {
    toast.error(e.message)
    files.value = []
  } finally {
    loading.value = false
  }
}

function enterDir(row) {
  pathStack.value.push((row.fid || row.fids))
  dir.value = (row.fid || row.fids)
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
function toggleCheck(row) {
  const id = row.fid || row.fids
  if (checked.value.has(id)) checked.value.delete(id)
  else checked.value.add(id)
}
async function pushAll() {
  const sel = files.value.filter((r) => checked.value.has(r.fid || r.fids))
  if (sel.length === 0) { toast.error('未勾选文件'); return }
  let ok = 0
  for (const r of sel) {
    if (r.dir) {
      const id = r.fid || r.fids
      pushing.value.add(id)
      try {
        const all = await collectFiles(id, '/' + r.file_name)
        for (const f of all) { try { await download(f); ok++ } catch (e) { /* 单个失败已提示 */ } }
      } finally { pushing.value.delete(id) }
    } else {
      try { await download(r); ok++ } catch (e) { /* 单个失败已提示 */ }
    }
  }
  if (ok > 0) toast.success(`已推送 ${ok} 个文件到 Aria2`)
}

async function download(row) {
  pushing.value.add((row.fid || row.fids))
  try {
    await ucApi.download({
      action: 'download',
      cookie: cookie.value.trim(),
      fs_id: (row.fid || row.fids),
      filename: row.file_name,
      dir: (downloadDir.value ? downloadDir.value + (row.relDir || '') : (row.relDir || '')) || undefined
    })
    toast.success(`「${row.file_name}」已推送`)
  } catch (e) {
    toast.error(e.message)
  } finally {
    pushing.value.delete((row.fid || row.fids))
  }
}
async function collectFiles(dirId, relDir, depth = 0) {
  const list = []
  if (depth > 6) return list
  const data = await ucApi.list(cookie.value.trim(), dirId)
  const items = (data && data.data && data.data.list) || []
  for (const r of items) {
    if (r.dir) { const sub = await collectFiles(r.fid || r.fids, relDir + '/' + r.file_name, depth + 1); list.push(...sub) }
    else list.push({ ...r, relDir })
  }
  return list
}
function pushBtnText() {
  const sel = files.value.filter((r) => checked.value.has(r.fid || r.fids))
  if (sel.length > 0 && sel.every((r) => r.dir)) return '点我推送文件夹到下载列表'
  if (sel.length > 0) return '点我推送文件到下载列表'
  return '推送勾选文件到 Aria2'
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <div class="page-title-sub font-calligraphy-行">UC网盘 · 星驰电走</div>
        <h1 class="page-title">UC云盘</h1>
      </div>
      <div class="vertical-note">网中取物 · 如掬清泉</div>
      <div class="head-line"></div>
      <SealStamp :text="'U'" :size="58" />
    </header>

    <div class="credential-card">
      <div class="field" style="margin-bottom: 12px;">
        <label class="field-label">UC网盘授权</label>
        <div class="field-row">
          <button class="ink-btn" @click="authorize">授权登录 ↗</button>
          <span class="tip">{{ authTip }}</span>
        </div>
      </div>
      <div class="field" style="margin-bottom: 12px;">
        <label class="field-label">Cookie</label>
        <div class="field-row">
          <input v-model="cookie" class="ink-input" type="password" placeholder="UC网盘 Cookie（隐藏显示，自动获取后回填）" @keyup.enter="load" />
          <button class="ink-btn ghost" @click="autoFetchCookie">自动获取</button>
        </div>
      </div>
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
          <tr v-for="row in files" :key="(row.fid || row.fids)" @dblclick="row.dir ? enterDir(row) : download(row)">
            <td>
              <input
                type="checkbox"
                :checked="checked.has(row.fid || row.fids)"
                @change="toggleCheck(row)"
                style="accent-color: var(--jade); width: 15px; height: 15px; cursor: pointer;"
              />
            </td>
            <td><span :class="row.dir ? 'folder-mark' : 'file-mark'">{{ row.dir ? '▣' : '▤' }}</span></td>
            <td class="fname font-body-楷">{{ row.file_name }}</td>
            <td>{{ row.dir ? (folderSizes[row.fid || row.fids] ?? '…') : formatSize(row.size) }}</td>
            <td>
              <button
                v-if="!row.dir"
                class="ink-btn ghost small"
                :disabled="pushing.has((row.fid || row.fids))"
                @click.stop="download(row)"
              >{{ pushing.has((row.fid || row.fids)) ? '推送中' : '下载' }}</button>
              <button v-else class="ink-btn ghost small" @click.stop="enterDir(row)">进入</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="files.length === 0 && !loading" class="empty-ink">
        <div class="char">空</div>
        <div class="hint">未取到文件 · 请填入有效 Cookie 后点「获取列表」</div>
      </div>
    </div>

    <DirPickerModal v-model="showDirPicker" @select="(p) => (downloadDir = p)" />
  </div>
</template>
