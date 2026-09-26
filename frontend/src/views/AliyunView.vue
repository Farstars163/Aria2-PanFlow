<script setup>
import { ref, onMounted } from 'vue'
import { aliyunApi, getDownloadDir } from '@/api/client'
import { toast } from '@/api/toast'
import DirPickerModal from '@/components/DirPickerModal.vue'
import SealStamp from '@/components/SealStamp.vue'
import PanServicePanel from '@/components/PanServicePanel.vue'

const files = ref([])          // 当前目录列表
const loading = ref(false)
const pushing = ref(false)
const checked = ref(new Set()) // 已勾选的 file_id
const pathStack = ref([])      // 目录栈(根为 root)
const downloadDir = ref(getDownloadDir())
const showDirPicker = ref(false)

onMounted(() => load('root'))

/** 加载目录: 阿里返回 {items:[{file_id,name,type}]} */
async function load(dirId) {
  loading.value = true
  try {
    const res = await aliyunApi.list(dirId)
    files.value = (res && res.items) || []
    checked.value = new Set()
  } catch (e) {
    toast.error('阿里云盘服务未启动: ' + e.message)
    files.value = []
  } finally {
    loading.value = false
  }
}

/** 服务停止: 立即清空列表(无需刷新页面) */
function clearFiles() {
  files.value = []
  checked.value = new Set()
}

/** 进入文件夹(与夸克/百度一致) */
function enterDir(item) {
  if (item.type !== 'folder') return
  pathStack.value.push(item.file_id)
  load(item.file_id)
}

/** 返回上级 */
function goBack() {
  if (pathStack.value.length === 0) return
  pathStack.value.pop()
  load(pathStack.value.length > 0 ? pathStack.value[pathStack.value.length - 1] : 'root')
}

function toggleCheck(item) {
  if (checked.value.has(item.file_id)) checked.value.delete(item.file_id)
  else checked.value.add(item.file_id)
}

/** 单文件推送: 校验 Python 服务返回的 success, 避免"假成功" */
async function download(item) {
  const res = await aliyunApi.download({
    action: 'download',
    file_id: item.file_id,
    filename: item.name,
    dir: (downloadDir.value ? downloadDir.value + (item.relDir || '') : (item.relDir || '')) || undefined
  })
  if (res && res.success === false) throw new Error(res.msg || '推送失败')
}

async function pushAll() {
  const selected = files.value.filter((item) => checked.value.has(item.file_id))
  if (selected.length === 0) { toast.error('未勾选文件'); return }

  pushing.value = true
  const filesToPush = []
  try {
    for (const it of selected) {
      if (it.type === 'folder') {
        const sub = await collectFiles(it.file_id, '/' + it.name)
        filesToPush.push(...sub)
      } else {
        filesToPush.push(it)
      }
    }
    let ok = 0
    for (const f of filesToPush) {
      try {
        await download(f)
        ok++
      } catch (e) {
        toast.error(`「${f.name}」${e.message}`)
      }
    }
    if (ok > 0) toast.success(`已推送 ${ok} 个文件到 Aria2`)
  } finally {
    pushing.value = false
  }
}

function pushBtnText() {
  const sel = files.value.filter((item) => checked.value.has(item.file_id))
  if (sel.length > 0 && sel.every((item) => item.type === 'folder')) return '点我推送文件夹到下载列表'
  if (sel.length > 0) return '点我推送文件到下载列表'
  return '推送勾选文件到 Aria2'
}

/** 递归收集文件夹下所有文件(限制深度, 防列表爆炸) */
async function collectFiles(dirId, relDir, depth = 0) {
  const list = []
  if (depth > 6) return list
  const res = await aliyunApi.list(dirId)
  const items = (res && res.items) || []
  for (const it of items) {
    if (it.type === 'folder') {
      const sub = await collectFiles(it.file_id, relDir + '/' + it.name, depth + 1)
      list.push(...sub)
    } else {
      list.push({ ...it, relDir })
    }
  }
  return list
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <div class="page-title-sub font-calligraphy-行">阿里云盘 · 青绿山水</div>
        <h1 class="page-title">阿里云盘</h1>
      </div>
      <div class="vertical-note">由 Aligo 接管授权</div>
      <div class="head-line"></div>
      <SealStamp :text="'阿'" :size="58" />
    </header>

    <PanServicePanel service="aliyun" name="阿里云盘" @started="() => load('root')" @stopped="clearFiles" />

    <div class="credential-card">
      <div class="field-row">
        <button v-if="pathStack.length > 0" class="ink-btn ghost" @click="goBack">返回上级 ←</button>
        <button class="ink-btn seal" :disabled="pushing" @click="pushAll">
          {{ pushing ? '推送中…' : pushBtnText() }}
        </button>
        <button class="ink-btn ghost" @click="showDirPicker = true">
          下载目录: {{ downloadDir || 'aria2 默认' }}
        </button>
      </div>
    </div>

    <div class="ink-table-wrap">
      <table class="ink-table">
        <thead>
          <tr>
            <th style="width: 40px;"></th>
            <th style="width: 46px;">形</th>
            <th>名</th>
            <th style="width: 170px;">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in files" :key="item.file_id">
            <td>
              <input
                type="checkbox"
                :checked="checked.has(item.file_id)"
                @change="toggleCheck(item)"
                style="accent-color: var(--jade); width: 16px; height: 16px; cursor: pointer;"
              />
            </td>
            <td><span :class="item.type === 'folder' ? 'folder-mark' : 'file-mark'">{{ item.type === 'folder' ? '▣' : '▤' }}</span></td>
            <td class="fname font-body-楷">{{ item.name }}</td>
            <td>
              <button
                v-if="item.type === 'file'"
                class="ink-btn ghost small"
                :disabled="pushing"
                @click.stop="download(item)"
              >下载</button>
              <template v-else>
                <button class="ink-btn ghost small" @click.stop="enterDir(item)">进入</button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="files.length === 0 && !loading" class="empty-ink">
        <div class="char">虚</div>
        <div class="hint">{{ pathStack.length > 0 ? '此目录为空 · 点击「返回上级」' : '未取到目录 · 请确认阿里云盘服务已启动并完成授权' }}</div>
      </div>
    </div>

    <DirPickerModal v-model="showDirPicker" @select="(p) => (downloadDir = p)" />
  </div>
</template>
