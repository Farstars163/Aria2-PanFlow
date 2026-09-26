<script setup>
import { ref, onMounted } from 'vue'
import { pan123Api, formatSize, getDownloadDir } from '@/api/client'
import { toast } from '@/api/toast'
import DirPickerModal from '@/components/DirPickerModal.vue'
import SealStamp from '@/components/SealStamp.vue'
import PanServicePanel from '@/components/PanServicePanel.vue'

const files = ref([])
const loading = ref(false)
const pushing = ref(new Set())
const downloadDir = ref(getDownloadDir())
const showDirPicker = ref(false)
const pathStack = ref([0])
const folderSizes = ref({})

onMounted(() => load())

async function calcFolderSize(parentId, depth = 0) {
  if (depth > 10) return 0
  const res = await pan123Api.list(String(parentId))
  const items = (res && res.code === 0 && res.data) || []
  let total = 0
  for (const r of items) { if (rowType(r) === 1) total += await calcFolderSize(r.fileId || r.FileId, depth + 1); else total += Number(r.size !== undefined ? r.size : r.Size) || 0 }
  return total
}
async function fillFolderSizes(list) {
  for (const r of list) { if (rowType(r) === 1) { const id = r.fileId || r.FileId; folderSizes.value[id] = '…'; try { folderSizes.value[id] = formatSize(await calcFolderSize(id)) } catch (e) { folderSizes.value[id] = '' } } }
}

async function load() {
  loading.value = true
  try {
    const res = await pan123Api.list(String(pathStack.value[pathStack.value.length - 1]))
    if (res && res.code === 0) {
      files.value = res.data || []
      fillFolderSizes(files.value)
    } else {
      toast.error((res && (res.message || res.msg)) || '获取列表失败')
      files.value = []
    }
  } catch (e) {
    toast.error('123云盘服务未启动: ' + e.message)
    files.value = []
  } finally {
    loading.value = false
  }
}

function enterDir(row) {
  const id = row.fileId || row.FileId
  pathStack.value.push(id)
  load()
}

function goBack() {
  if (pathStack.value.length <= 1) return
  pathStack.value.pop()
  load()
}

/** 服务停止: 立即清空列表(自动刷新页面效果) */
function clearFiles() {
  files.value = []
  checked.value = new Set()
}

// 批量勾选推送
const checked = ref(new Set())
function toggleCheck(row) {
  const id = row.fileId || row.FileId
  if (checked.value.has(id)) checked.value.delete(id)
  else checked.value.add(id)
}
function pushBtnText() {
  const sel = files.value.filter((r) => checked.value.has(r.fileId || r.FileId))
  if (sel.length > 0 && sel.every((r) => rowType(r) === 1)) return '点我推送文件夹到下载列表'
  if (sel.length > 0) return '点我推送文件到下载列表'
  return '推送勾选文件到 Aria2'
}
async function pushAll() {
  const list = files.value.filter((r) => checked.value.has(r.fileId || r.FileId))
  if (list.length === 0) { toast.error('未勾选文件'); return }
  let ok = 0
  for (const r of list) {
    if (rowType(r) === 1) {
      const sub = await collectFiles(r.fileId || r.FileId, '/' + (r.fileName || r.FileName))
      for (const f of sub) { try { await download(f); ok++ } catch (e) {} }
    } else {
      try { await download(r); ok++ } catch (e) { /* 单个失败已提示 */ }
    }
  }
  if (ok > 0) toast.success(`已推送 ${ok} 个文件到 Aria2`)
}

function rowType(row) {
  const t = row.type !== undefined ? row.type : row.Type
  return Number(t)
}

async function download(row) {
  const id = row.fileId || row.FileId
  pushing.value.add(id)
  try {
    const payload = {
      ...row,
      dir: (downloadDir.value ? downloadDir.value + (row.relDir || '') : (row.relDir || '') || undefined)
    }
    const res = await pan123Api.download(payload)
    toast.success(`「${row.fileName || row.FileName}」已推送`)
  } catch (e) {
    toast.error(e.message)
  } finally {
    pushing.value.delete(id)
  }
}

async function collectFiles(parentId, relDir, depth = 0) {
  const list = []
  if (depth > 6) return list
  const res = await pan123Api.list(String(parentId))
  const items = (res && res.code === 0 && res.data) || []
  for (const r of items) {
    if (rowType(r) === 1) { const sub = await collectFiles(r.fileId || r.FileId, relDir + '/' + (r.fileName || r.FileName), depth + 1); list.push(...sub) }
    else list.push({ ...r, relDir })
  }
  return list
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <div class="page-title-sub font-calligraphy-行">123云盘 · 三才聚气</div>
        <h1 class="page-title">123云盘</h1>
      </div>
      <div class="vertical-note">取物于云 · 落墨于舟</div>
      <div class="head-line"></div>
      <SealStamp :text="'壹'" :size="58" />
    </header>

    <PanServicePanel service="pan123" name="123云盘" @started="load" @stopped="clearFiles" />

    <div class="credential-card">
      <div class="field-row">
        <button class="ink-btn seal" :disabled="loading" @click="load">{{ loading ? '取物中…' : '刷新列表' }}</button>
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
          <tr v-for="row in files" :key="row.fileId || row.FileId" @dblclick="rowType(row) === 1 ? enterDir(row) : download(row)">
            <td>
              <input
                type="checkbox"
                :checked="checked.has(row.fileId || row.FileId)"
                @change="toggleCheck(row)"
                style="accent-color: var(--jade); width: 15px; height: 15px; cursor: pointer;"
              />
            </td>
            <td><span :class="rowType(row) === 1 ? 'folder-mark' : 'file-mark'">{{ rowType(row) === 1 ? '▣' : '▤' }}</span></td>
            <td class="fname font-body-楷">{{ row.fileName || row.FileName }}</td>
            <td>{{ rowType(row) === 1 ? (folderSizes[row.fileId || row.FileId] ?? '…') : formatSize(row.size !== undefined ? row.size : row.Size) }}</td>
            <td>
              <button
                v-if="rowType(row) !== 1"
                class="ink-btn ghost small"
                :disabled="pushing.has(row.fileId || row.FileId)"
                @click.stop="download(row)"
              >{{ pushing.has(row.fileId || row.FileId) ? '推送中' : '下载' }}</button>
              <button v-else class="ink-btn ghost small" @click.stop="enterDir(row)">进入</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="files.length === 0 && !loading" class="empty-ink">
        <div class="char">隐</div>
        <div class="hint">未取到文件 · 请确认 123 云盘 Python 服务已启动</div>
      </div>
    </div>

    <DirPickerModal v-model="showDirPicker" @select="(p) => (downloadDir = p)" />
  </div>
</template>
