<script setup>
import { ref, onMounted } from 'vue'
import { mcloudApi, formatSize, getDownloadDir } from '@/api/client'
import { toast } from '@/api/toast'
import DirPickerModal from '@/components/DirPickerModal.vue'
import SealStamp from '@/components/SealStamp.vue'

const AUTH_KEY = 'panflow.mcloud.auth'
const authorization = ref(localStorage.getItem(AUTH_KEY) || '')
// 授权引导提示: 自动获取可用时引导点击「自动获取」; 失败时改回手动复制说明
const authTip = ref('点击授权打开中国移动云盘登录页；登录成功后点击「自动获取」自动抓取 Authorization。')
const dir = ref('')
const files = ref([])
const loading = ref(false)
const pushing = ref(new Set())
const downloadDir = ref(getDownloadDir())
const showDirPicker = ref(false)

// 目录栈: 用于双击文件夹进入 (根目录由后端映射为 "/")
const pathStack = ref([''])

const folderSizes = ref({})
async function calcFolderSize(dirId, depth = 0) {
  if (depth > 10) return 0
  const data = await mcloudApi.list(authorization.value.trim(), dirId)
  const items = (data && data.list) || []
  let total = 0
  for (const r of items) { if (r.dir) total += await calcFolderSize(r.id, depth + 1); else total += Number(r.size) || 0 }
  return total
}
async function fillFolderSizes(list) {
  for (const r of list) { if (r.dir) { folderSizes.value[r.id] = '…'; try { folderSizes.value[r.id] = formatSize(await calcFolderSize(r.id)) } catch (e) { folderSizes.value[r.id] = '' } } }
}

onMounted(() => { if (authorization.value) load() })

/** 授权跳转: 打开移动云盘登录页(即授权页), 在调试浏览器中登录后即可自动获取 */
function authorize() {
  window.open('https://yun.139.com/', '_blank')
  toast.info('登录成功后，点击「自动获取」按钮自动抓取 Authorization')
}

/** 自动获取 Authorization: 后端通过浏览器 CDP(调试端口 9222) 读取 yun.139.com cookie 中的 authorization */
async function autoFetchAuth() {
  try {
    const res = await mcloudApi.cdpCookie()
    if (res && res.authorization) {
      authorization.value = res.authorization
      localStorage.setItem(AUTH_KEY, res.authorization)
      toast.success('已自动获取 Authorization 并回填')
      load()
    } else {
      toast.error('未读取到有效 Authorization，请先在调试浏览器中完成移动云盘登录')
      authTip.value = '自动获取失败，请复制浏览器 Cookie 中 authorization 的值手动粘贴到下方输入框。'
    }
  } catch (e) {
    toast.error('自动获取失败: ' + e.message)
    authTip.value = '自动获取失败，请复制浏览器 Cookie 中 authorization 的值手动粘贴到下方输入框。'
  }
}

async function load() {
  if (!authorization.value.trim()) { toast.error('请先填入 Authorization'); return }
  loading.value = true
  try {
    const data = await mcloudApi.list(authorization.value.trim(), dir.value)
    // 后端已归一化为标准结构 {list:[{id,name,size,dir}]}
    files.value = (data && data.list) || []
    fillFolderSizes(files.value)
    localStorage.setItem(AUTH_KEY, authorization.value.trim())
    // 列表为空时输出原始响应到控制台, 便于诊断响应结构
    if (files.value.length === 0 && data && data.raw) {
      console.warn('[移动云盘] 列表为空, 原始响应:', JSON.stringify(data.raw))
    }
  } catch (e) {
    toast.error(e.message)
    files.value = []
  } finally {
    loading.value = false
  }
}

function enterDir(row) {
  if (!row.id) return
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
function toggleCheck(row) {
  if (checked.value.has(row.id)) checked.value.delete(row.id)
  else checked.value.add(row.id)
}
async function pushAll() {
  const sel = files.value.filter((r) => checked.value.has(r.id))
  if (sel.length === 0) { toast.error('未勾选文件'); return }
  const list = []
  for (const r of sel) {
    if (r.dir) { const sub = await collectFiles(r.id, '/' + r.name); list.push(...sub) }
    else list.push(r)
  }
  let ok = 0
  for (const f of list) { try { await download(f); ok++ } catch (e) { /* 单个失败已提示 */ } }
  if (ok > 0) toast.success(`已推送 ${ok} 个文件到 Aria2`)
}

async function download(row) {
  pushing.value.add(row.id)
  try {
    await mcloudApi.download({
      action: 'download',
      authorization: authorization.value.trim(),
      fileId: row.id,
      filename: row.name,
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
  const data = await mcloudApi.list(authorization.value.trim(), dirId)
  const items = (data && data.list) || []
  for (const r of items) {
    if (r.dir) { const sub = await collectFiles(r.id, relDir + '/' + r.name, depth + 1); list.push(...sub) }
    else list.push({ ...r, relDir })
  }
  return list
}
function pushBtnText() {
  const sel = files.value.filter((r) => checked.value.has(r.id))
  if (sel.length > 0 && sel.every((r) => r.dir)) return '点我推送文件夹到下载列表'
  if (sel.length > 0) return '点我推送文件到下载列表'
  return '推送勾选文件到 Aria2'
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <div class="page-title-sub font-calligraphy-行">中国移动云盘 · 移山填海</div>
        <h1 class="page-title">中国移动云盘</h1>
      </div>
      <div class="vertical-note">动若脱兔 · 移云借月</div>
      <div class="head-line"></div>
      <SealStamp :text="'移'" :size="58" />
    </header>

    <div class="credential-card">
      <div class="field" style="margin-bottom: 12px;">
        <label class="field-label">移动云盘授权</label>
        <div class="field-row">
          <button class="ink-btn" @click="authorize">授权登录 ↗</button>
          <span class="tip">{{ authTip }}</span>
        </div>
      </div>
      <div class="field" style="margin-bottom: 12px;">
        <label class="field-label">Authorization</label>
        <div class="field-row">
          <input v-model="authorization" class="ink-input" type="password" placeholder="移动云盘 Authorization（隐藏显示，自动获取后回填）" @keyup.enter="load" />
          <button class="ink-btn ghost" @click="autoFetchAuth">自动获取</button>
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
          <tr v-for="row in files" :key="row.id" @dblclick="row.dir ? enterDir(row) : download(row)">
            <td>
              <input
                type="checkbox"
                :checked="checked.has(row.id)"
                @change="toggleCheck(row)"
                style="accent-color: var(--jade); width: 15px; height: 15px; cursor: pointer;"
              />
            </td>
            <td><span :class="row.dir ? 'folder-mark' : 'file-mark'">{{ row.dir ? '▣' : '▤' }}</span></td>
            <td class="fname font-body-楷">{{ row.name }}</td>
            <td>{{ row.dir ? (folderSizes[row.id] ?? '…') : formatSize(row.size) }}</td>
            <td>
              <button
                v-if="!row.dir"
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
        <div class="hint">未取到文件 · 请填入有效 Authorization 后点「获取列表」</div>
      </div>
    </div>

    <DirPickerModal v-model="showDirPicker" @select="(p) => (downloadDir = p)" />
  </div>
</template>
