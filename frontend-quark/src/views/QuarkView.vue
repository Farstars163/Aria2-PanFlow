<script setup>
import { ref, onMounted } from 'vue'
import { quarkApi, formatSize, getDownloadDir } from '@/api/client'
import { toast } from '@/api/toast'
import DirPickerModal from '@/components/DirPickerModal.vue'
import SealStamp from '@/components/SealStamp.vue'
import AuthLinks from '@/components/AuthLinks.vue'

const cookie = ref(localStorage.getItem('panflow.quark.cookie') || '')
const dir = ref('0')
const files = ref([])
const loading = ref(false)
const pushing = ref(new Set())
const downloadDir = ref(getDownloadDir())
const showDirPicker = ref(false)
const folderSizes = ref({})
async function calcFolderSize(dirId, depth = 0) {
  if (depth > 10) return 0
  const data = await quarkApi.list(cookie.value.trim(), dirId)
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

/** 授权跳转: 打开夸克网盘登录页(即授权页), 在调试浏览器中登录后即可自动获取 */
function authorize() {
  window.open('https://pan.quark.cn/', '_blank')
  toast.info('登录成功后，点击「自动获取 Cookie」按钮自动抓取 Cookie')
}

/** 自动获取夸克 cookie: 后端通过浏览器 CDP(调试端口 9222) 读取完整 cookie(含 HttpOnly) */
async function autoFetchCookie() {
  try {
    const res = await quarkApi.cdpCookie()
    if (typeof res === 'string' && res.length > 10) {
      cookie.value = res
      localStorage.setItem('panflow.quark.cookie', res)
      toast.success('已自动获取 Cookie 并回填')
      load()
    } else {
      toast.error('未读取到有效 Cookie，请确认已在调试浏览器中登录夸克网盘')
    }
  } catch (e) {
    toast.error('自动获取失败: ' + e.message)
  }
}

async function load() {
  if (!cookie.value.trim()) { toast.error('请先填入 Cookie'); return }
  loading.value = true
  try {
    const data = await quarkApi.list(cookie.value.trim(), dir.value)
    // 后端返回夸克原始结构: {code, message, timestamp, data:{list:[...]}}
    files.value = (data && data.data && data.data.list) || []
    fillFolderSizes(files.value)
    localStorage.setItem('panflow.quark.cookie', cookie.value.trim())
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
  const todo = []
  for (const r of sel) {
    if (r.dir) {
      const sub = await collectFiles(r.fid || r.fids, '/' + r.file_name)
      todo.push(...sub)
    } else {
      todo.push(r)
    }
  }
  if (todo.length === 0) { toast.info('所选内容为空'); return }
  let ok = 0
  for (const f of todo) {
    try { await download(f); ok++ } catch (e) { /* 单个失败已提示 */ }
  }
  if (ok > 0) toast.success(`已推送 ${ok} 个文件到 Aria2`)
}
function pushBtnText() {
  const sel = files.value.filter((r) => checked.value.has(r.fid || r.fids))
  if (sel.length > 0 && sel.every((r) => r.dir)) return '点我推送文件夹到下载列表'
  if (sel.length > 0) return '点我推送文件到下载列表'
  return '推送勾选文件到 Aria2'
}

async function download(row) {
  pushing.value.add((row.fid || row.fids))
  try {
    await quarkApi.download({
      action: 'download',
      cookie: cookie.value.trim(),
      fs_id: (row.fid || row.fids),
      filename: row.file_name,
      dir: downloadDir.value ? downloadDir.value + (row.relDir || '') : (row.relDir || '') || undefined
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
  const data = await quarkApi.list(cookie.value.trim(), dirId)
  const items = (data && data.data && data.data.list) || []
  for (const r of items) {
    if (r.dir) { const sub = await collectFiles(r.fid || r.fids, relDir + '/' + r.file_name, depth + 1); list.push(...sub) }
    else list.push({ ...r, relDir })
  }
  return list
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <div class="page-title-sub font-calligraphy-行">夸克网盘 · 星移斗转</div>
        <h1 class="page-title">夸克云盘</h1>
      </div>
      <div class="vertical-note">夸父逐日 · 我逐直链</div>
      <div class="head-line"></div>
      <SealStamp :text="'夸'" :size="58" />
    </header>

    <div class="credential-card">
      <div class="field" style="margin-bottom: 12px;">
        <label class="field-label">夸克网盘授权</label>
        <button class="ink-btn" @click="authorize">授权登录 ↗</button>
        <label class="field-label">Cookie</label>
        <input v-model="cookie" class="ink-input" type="password" placeholder="夸克网盘 Cookie（隐藏显示，自动获取后回填）" />
        <div class="field-row" style="margin-top: 8px;">
          <button class="ink-btn ghost small" @click="autoFetchCookie">自动获取 Cookie</button>
          <span class="tip">凭据仅存于本机浏览器 localStorage；Cookie 失效时接口将返回 401。</span>
        </div>
      </div>
      <AuthLinks current="quark" style="margin-bottom: 12px;" />
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
