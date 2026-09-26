<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { taskStore } from '@/api/tasks'
import { tasksApi, formatSpeed, taskName, getDownloadDir, setDownloadDir, STATUS_META, settingsApi } from '@/api/client'
import { toast } from '@/api/toast'
import TaskCard from '@/components/TaskCard.vue'
import DirPickerModal from '@/components/DirPickerModal.vue'
import SealStamp from '@/components/SealStamp.vue'

/* ---------- 添加下载弹窗 ---------- */
const showAdd = ref(false)
const addUrl = ref('')
const addOut = ref('')
const addDir = ref(getDownloadDir())
const showDirPicker = ref(false)
const adding = ref(false)

function openAdd() {
  addUrl.value = ''
  addOut.value = ''
  addDir.value = getDownloadDir()
  showAdd.value = true
}

function pickDir(dir) {
  addDir.value = dir
  setDownloadDir(dir)
}

/* ---------- 代理设置(下载走代理, 加速国外链接) ---------- */
const proxyAddress = ref('')
async function loadProxy() {
  try {
    const res = await settingsApi.getProxy()
    proxyAddress.value = (res && res.proxy) || ''
  } catch (e) { /* 静默 */ }
}
async function saveProxy() {
  const p = proxyAddress.value.trim()
  // 格式检测: 主机:端口
  if (p) {
    const parts = p.split(':')
    const port = Number(parts[parts.length - 1])
    if (parts.length !== 2 || parts[0].trim() === '' || !(port >= 1 && port <= 65535)) {
      toast.error('代理地址格式不正确，应为 主机:端口，如 127.0.0.1:30808')
      return
    }
  }
  try {
    await settingsApi.setProxy(p)
    toast.success('代理设置已保存')
    // 保存后连通性测试: 不通则提示
    if (p) {
      try {
        const t = await settingsApi.testProxy(p)
        if (t && t.reachable === true) {
          toast.success('代理连通性测试通过')
        } else if (t && t.formatError) {
          toast.error(t.data.formatError)
        } else {
          toast.error('代理不可达，下载将不走代理，请确认代理已开启')
        }
      } catch (e2) {
        toast.error('连通性测试失败：' + e2.message)
      }
    }
  } catch (e) {
    toast.error('保存失败: ' + e.message)
  }
}
onMounted(() => {
  loadProxy()
  loadGlobalSettings()
})

/* ---------- 全局设置 ---------- */
const showGlobalSettings = ref(false)
const gs = ref({
  maxConcurrent: 5,
  maxConnections: 16,
  continue: true,
  ua: '',
  rpcPort: 16800,
  rpcSecret: '',
  servicePort: 18080,
  autoJump: true,
  notifyDone: true,
  confirmDelete: true
})
async function loadGlobalSettings() {
  try {
    const t = await settingsApi.getGlobal()
    if (t && t.settings) gs.value = { ...gs.value, ...t.settings }
  } catch (e) { /* 静默 */ }
}
async function saveGlobalSettings() {
  try {
    await settingsApi.setGlobal(gs.value)
    toast.success('全局设置已保存')
  } catch (e) {
    toast.error('保存失败: ' + e.message)
  }
}

// 下载完成通知(受全局设置"下载完成后通知"开关控制)
watch(
  () => taskStore.complete.length,
  (n, o) => {
    if (n > o && gs.value.notifyDone) {
      const t = taskStore.complete[0]
      toast.success('下载完成：' + (t && (t.out || t.name || t.gid || '')))
    }
  }
)

/* ---------- BT 任务侧边栏(点击"详情"显示, 默认隐藏, 每 2.5 秒实时刷新) ---------- */
const sidebarTask = ref(null)
const sidebarPeers = ref([])
let sidebarTimer = null
async function refreshSidebar() {
  const gid = sidebarTask.value && sidebarTask.value.gid
  if (!gid) return
  try {
    // 从任务池取最新状态(上传速率/做种数实时更新)
    const cur = [...activeList.value, ...waitingList.value, ...completeList.value].find((t) => t.gid === gid)
    if (cur) sidebarTask.value = cur
    const res = await tasksApi.getPeers(gid)
    if (Array.isArray(res)) sidebarPeers.value = res
  } catch (e) { /* 静默 */ }
}
async function openSidebar(task) {
  sidebarTask.value = task
  sidebarPeers.value = []
  await refreshSidebar()
  if (sidebarTimer) clearInterval(sidebarTimer)
  sidebarTimer = setInterval(refreshSidebar, 2500)
}
function closeSidebar() {
  if (sidebarTimer) { clearInterval(sidebarTimer); sidebarTimer = null }
  sidebarTask.value = null
}
onUnmounted(() => { if (sidebarTimer) clearInterval(sidebarTimer) })

/** 结束做种任务(停止做种, 保留已下载文件) */
async function finishSeed(gid) {
  try { await tasksApi.finish(gid); toast.success('做种已结束') } catch (e) { toast.error(e.message) }
}

async function submitAdd() {
  const urls = addUrl.value
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
  if (urls.length === 0) {
    toast.error('请先填入下载链接')
    return
  }
  adding.value = true
  let ok = 0
  try {
    for (const url of urls) {
      try {
        await tasksApi.add({
          url,
          dir: addDir.value || undefined,
          out: addOut.value || undefined
        })
        ok++
      } catch (e) {
        toast.error(`「${url.slice(0, 32)}…」${e.message}`)
      }
    }
    if (ok > 0) {
      toast.success(`已落墨 ${ok} 个下载任务`)
      showAdd.value = false
    }
  } finally {
    adding.value = false
  }
}

// 种子文件上传(.torrent): 读为 base64 提交后端 aria2.addTorrent; 磁力链接直接粘贴到上方输入框即可
const torrentFile = ref(null)
const torrentInput = ref(null)
function onTorrentPick(e) {
  const file = e.target.files[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    torrentFile.value = { name: file.name, b64: String(reader.result).split(',')[1] }
  }
  reader.readAsDataURL(file)
}
async function submitTorrent() {
  if (!torrentFile.value) { toast.error('请先选择种子文件'); return }
  adding.value = true
  try {
    await tasksApi.addTorrent({
      torrent: torrentFile.value.b64,
      dir: addDir.value || undefined,
      out: torrentFile.value.name.replace(/\.torrent$/i, '') || undefined
    })
    toast.success('种子任务已提交')
    showAdd.value = false
    torrentFile.value = null
  } catch (e) {
    toast.error(e.message)
  } finally {
    adding.value = false
  }
}

/* ---------- 任务操作 ---------- */
async function pause(gid) {
  try { await tasksApi.pause(gid); toast.info('已暂停') } catch (e) { toast.error(e.message) }
}
async function unpause(gid) {
  try { await tasksApi.unpause(gid); toast.info('已续行') } catch (e) { toast.error(e.message) }
}
async function remove(gid) {
  // 全局设置: 默认无需确认; 关闭"删除任务前无需确认"时弹出确认
  if (!gs.value.confirmDelete && !window.confirm('确定移除该任务？（本地文件与记录将一并清理）')) return
  try { await tasksApi.remove(gid); toast.success('任务已移除') } catch (e) { toast.error(e.message) }
}
async function clear(gid) {
  try { await tasksApi.clear(gid); toast.info('已清墨') } catch (e) { toast.error(e.message) }
}

function clearAllCompleted() {
  const gids = taskStore.complete.map((t) => t.gid)
  if (gids.length === 0) { toast.info('本无尘埃可拂'); return }
  gids.forEach((gid) => tasksApi.clear(gid).catch(() => {}))
  toast.success(`已拂去 ${gids.length} 笔旧墨`)
}

/* ---------- 数据 ---------- */
const totalSpeed = computed(() => formatSpeed(taskStore.totalSpeed))
const activeCount = computed(() => taskStore.active.length)
const waitingCount = computed(() => taskStore.waiting.length)
const completeCount = computed(() => taskStore.complete.length)

const activeList = computed(() => taskStore.active)
const waitingList = computed(() => taskStore.waiting)
const completeList = computed(() => taskStore.complete)

// 启动时 REST 拉取一次全量, 弥补 WebSocket 未连接前的时间窗
onMounted(async () => {
  try {
    const data = await tasksApi.overview()
    if (data) {
      taskStore.active = data.active || []
      taskStore.waiting = data.waiting || []
      taskStore.complete = data.complete || []
      taskStore.totalSpeed = data.totalSpeed || '0'
    }
  } catch (e) {
    /* aria2 未启动时静默, WebSocket 会持续重连 */
  }
})

function statusTone(s) {
  return (STATUS_META[s] || STATUS_META.waiting).tone
}
</script>

<template>
  <div class="page">
    <!-- 页首 -->
    <header class="page-head">
      <div>
        <div class="page-title-sub font-calligraphy-行">一砚烟雨 · 万卷归舟</div>
        <h1 class="page-title">下载管理</h1>
      </div>
      <div class="vertical-note">山静似太古 · 日长如小年</div>
      <div class="head-line"></div>
      <SealStamp :text="'舟'" :size="58" />
    </header>

    <!-- 简易使用提示 -->
    <section class="usage-tip">
      <div class="usage-left">
        <SealStamp :text="'用'" :size="40" />
      </div>
      <div class="usage-body">
        <div class="usage-title font-calligraphy-行">简用之法 · 三步通</div>
        <ol class="usage-steps font-body-楷">
          <li><b>添舟入流</b>：点「＋ 添加下载」粘贴链接（支持多行）；或在各个网盘页勾选文件，「推送勾选文件到 Aria2」一键批量下载。</li>
          <li><b>网盘授权</b>：百度 / 夸克 / 123 / 阿里等均在各自页面完成授权——扫码或「自动填充 / 自动获取」。</li>
          <li><b>泊舟归岸</b>：进行中任务可暂停 / 续行；移除任务将同步清理本地痕迹；关闭浏览器页面 4 分钟后服务进程自动停止（避免重复启动进程出现卡死）。</li>
        </ol>
      </div>
    </section>

    <!-- 统计 -->
    <section class="stat-row">
      <div class="stat-card hot">
        <div class="stat-label">总墨流 · 下载速度</div>
        <div class="stat-value">{{ totalSpeed }}</div>
      </div>
      <div class="stat-card jade">
        <div class="stat-label">进行中</div>
        <div class="stat-value">{{ activeCount }}<span class="unit">笔</span></div>
      </div>
      <div class="stat-card gold">
        <div class="stat-label">等待中</div>
        <div class="stat-value">{{ waitingCount }}<span class="unit">笔</span></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">已完成</div>
        <div class="stat-value">{{ completeCount }}<span class="unit">笔</span></div>
      </div>
    </section>

    <!-- 操作 -->
    <div style="display: flex; gap: 12px; margin-bottom: 8px;">
      <button class="ink-btn seal" @click="openAdd">＋ 添加下载</button>
      <button class="ink-btn ghost" @click="showGlobalSettings = true">全局设置</button>
      <button class="ink-btn" @click="clearAllCompleted">拂尘 · 清空完成</button>
    </div>

    <!-- 进行中 -->
    <div class="section-head">
      <span class="dot jade"></span>
      <span class="title">进行中</span>
      <span class="count">{{ activeList.length }}</span>
      <span class="line"></span>
    </div>
    <div v-if="activeList.length === 0" class="empty-ink">
      <div class="char">静</div>
      <div class="hint">万籁俱寂 · 尚无奔流</div>
    </div>
    <TaskCard
      v-for="t in activeList"
      :key="t.gid"
      :task="t"
      @pause="pause"
      @remove="remove"
      @peers="openSidebar"
      @finish="finishSeed"
    />

    <!-- 等待中 -->
    <div class="section-head">
      <span class="dot"></span>
      <span class="title">等待中</span>
      <span class="count">{{ waitingList.length }}</span>
      <span class="line"></span>
    </div>
    <div v-if="waitingList.length === 0" class="empty-ink" style="padding: 40px;">
      <div class="hint">无待发之舟</div>
    </div>
    <TaskCard
      v-for="t in waitingList"
      :key="t.gid"
      :task="t"
      @unpause="unpause"
      @remove="remove"
      @peers="openSidebar"
      @finish="finishSeed"
    />

    <!-- 已完成 -->
    <div class="section-head">
      <span class="dot red"></span>
      <span class="title">已完成</span>
      <span class="count">{{ completeList.length }}</span>
      <span class="line"></span>
    </div>
    <div v-if="completeList.length === 0" class="empty-ink" style="padding: 40px;">
      <div class="hint">归舟未返 · 尚无藏卷</div>
    </div>
    <TaskCard
      v-for="t in completeList"
      :key="t.gid"
      :task="t"
      @clear="clear"
      @peers="openSidebar"
      @finish="finishSeed"
    />

    <!-- 添加下载弹窗 -->
    <div v-if="showAdd" class="overlay" @click.self="showAdd = false">
      <div class="modal">
        <div class="modal-head">
          <div class="modal-title"><span>◉</span> 添笔新墨 · 下载任务</div>
          <button class="modal-close" @click="showAdd = false">✕</button>
        </div>

        <div class="field">
          <label class="field-label">下载链接（可多行）</label>
          <textarea v-model="addUrl" class="ink-textarea" placeholder="http://example.com/file.zip&#10;每行一个链接"></textarea>
        </div>

        <div class="field">
          <label class="field-label">另存为（可选，留空则用原文件名）</label>
          <input v-model="addOut" class="ink-input" placeholder="file.zip" />
        </div>

        <div class="field">
          <label class="field-label">存放目录</label>
          <div class="field-row">
            <input v-model="addDir" class="ink-input" placeholder="留空为 aria2 默认目录" />
            <button class="ink-btn ghost" @click="showDirPicker = true">浏览</button>
          </div>
        </div>

        <div class="field">
          <label class="field-label">代理地址（可加速国外下载，留空则不走代理）</label>
          <div class="field-row">
            <input v-model="proxyAddress" class="ink-input" style="flex: 1;" placeholder="例如 127.0.0.1:30808" />
            <button class="ink-btn ghost" @click="saveProxy">保存</button>
          </div>
          <label class="field-label">或上传种子文件 (.torrent) —— 磁力链接直接粘贴到上方下载链接框</label>
          <div class="field-row">
            <input ref="torrentInput" type="file" accept=".torrent" style="display: none;" @change="onTorrentPick" />
            <button class="ink-btn ghost" @click="torrentInput.click()">选择种子文件</button>
            <span class="tip" style="flex: 1;">{{ torrentFile ? torrentFile.name : '未选择文件' }}</span>
            <button class="ink-btn ghost" :disabled="adding || !torrentFile" @click="submitTorrent">提交种子</button>
          </div>
        </div>

        <div class="modal-foot">
          <button class="ink-btn" @click="showAdd = false">作罢</button>
          <button class="ink-btn seal" :disabled="adding || !addUrl.trim()" @click="submitAdd">
            {{ adding ? '落墨中…' : '落墨提交' }}
          </button>
        </div>
      </div>
    </div>

    <DirPickerModal v-model="showDirPicker" @select="pickDir" />

    <!-- 全局设置弹窗 -->
    <div v-if="showGlobalSettings" class="overlay" @click.self="showGlobalSettings = false">
      <div class="modal">
        <div class="modal-head">
          <div class="modal-title"><span>◉</span> 全局设置</div>
          <button class="modal-close" @click="showGlobalSettings = false">✕</button>
        </div>
        <div class="modal-body">
          <div class="field">
            <label class="field-label">同时下载最大任务数</label>
            <input v-model.number="gs.maxConcurrent" class="ink-input" type="number" min="1" />
          </div>
          <div class="field">
            <label class="field-label">每服务器最大连接数</label>
            <input v-model.number="gs.maxConnections" class="ink-input" type="number" min="1" />
          </div>
          <div class="field">
            <label class="field-label"><input type="checkbox" v-model="gs.continue" style="margin-right:6px;" />断点续传</label>
          </div>
          <div class="field">
            <label class="field-label">下载 UA（仅非网盘文件，网盘文件保持默认）</label>
            <input v-model="gs.ua" class="ink-input" placeholder="留空保持 aria2 默认" />
          </div>
          <div class="field">
            <label class="field-label"><input type="checkbox" v-model="gs.autoJump" style="margin-right:6px;" />新建任务后自动跳转到下载页面</label>
          </div>
          <div class="field">
            <label class="field-label"><input type="checkbox" v-model="gs.notifyDone" style="margin-right:6px;" />下载完成后通知</label>
          </div>
          <div class="field">
            <label class="field-label"><input type="checkbox" v-model="gs.confirmDelete" style="margin-right:6px;" />删除任务前无需确认</label>
          </div>
        </div>
        <div class="modal-foot">
          <button class="ink-btn" @click="saveGlobalSettings">保存</button>
          <button class="ink-btn ghost" @click="showGlobalSettings = false">取消</button>
        </div>
      </div>
    </div>

    <!-- BT 任务侧边栏(种子详情, 点击任务"详情"显示, 默认隐藏, 每 2.5 秒实时刷新) -->
    <div v-if="sidebarTask" class="peer-sidebar">
      <div class="sidebar-head">
        <div class="sidebar-title"><span class="brush-dot" style="background: var(--seal);"></span>种子详情</div>
        <button class="modal-close" @click="closeSidebar">✕</button>
      </div>
      <div class="sidebar-name">{{ taskName(sidebarTask) }}</div>
      <div class="stat-grid">
        <div class="stat-card">
          <div class="stat-label">下载速度</div>
          <div class="stat-value ink">{{ Number(sidebarTask.downloadSpeed) > 0 ? formatSpeed(sidebarTask.downloadSpeed) : '0 B/s' }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">上传速度</div>
          <div class="stat-value gold">{{ Number(sidebarTask.uploadSpeed) > 0 ? formatSpeed(sidebarTask.uploadSpeed) : '0 B/s' }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Seeder</div>
          <div class="stat-value">{{ sidebarTask.numSeeders !== undefined ? sidebarTask.numSeeders : '-' }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">连接数</div>
          <div class="stat-value">{{ sidebarTask.connections !== undefined ? sidebarTask.connections : '-' }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">已上传</div>
          <div class="stat-value">{{ Number(sidebarTask.uploadLength) > 0 ? formatSize(sidebarTask.uploadLength) : '0 B' }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">进度</div>
          <div class="stat-value">{{ Number(sidebarTask.totalLength) > 0 ? ((Number(sidebarTask.completedLength) / Number(sidebarTask.totalLength)) * 100).toFixed(1) + '%' : '-' }}</div>
        </div>
      </div>
      <div class="peer-section">
        <div class="peer-head">Peer 列表 <span class="peer-count">{{ sidebarPeers.length }}</span></div>
        <div class="peer-list">
          <div v-for="(p, i) in sidebarPeers" :key="i" class="peer-item">
            <div class="peer-ip">{{ p.ip || p.peerId || '-' }}</div>
            <div class="peer-meta">
              <span v-if="p.percentage !== undefined">{{ (p.percentage || 0) + '%' }}</span>
              <span v-if="Number(p.uploadSpeed) > 0"> ↑{{ formatSpeed(p.uploadSpeed) }}</span>
              <span v-if="Number(p.downloadSpeed) > 0"> ↓{{ formatSpeed(p.downloadSpeed) }}</span>
            </div>
          </div>
          <div v-if="sidebarPeers.length === 0" class="peer-empty">暂无连接</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* BT 任务侧边栏(种子详情, 高级美观) */
.peer-sidebar {
  position: fixed;
  right: 0;
  top: 0;
  bottom: 0;
  width: 340px;
  background: linear-gradient(180deg, #fdfaf3 0%, #f7f2e7 100%);
  border-left: 1px solid rgba(26, 26, 26, 0.1);
  box-shadow: -8px 0 24px rgba(0, 0, 0, 0.12);
  z-index: 200;
  padding: 20px;
  overflow-y: auto;
}
.sidebar-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px dashed rgba(26, 26, 26, 0.15);
  padding-bottom: 10px;
}
.sidebar-title {
  font-size: 17px;
  letter-spacing: 3px;
  color: #333;
  display: flex;
  align-items: center;
  gap: 8px;
}
.sidebar-name { font-size: 14px; color: #555; margin: 12px 0; }
.stat-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin: 12px 0;
}
.stat-card {
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid rgba(26, 26, 26, 0.08);
  border-radius: 8px;
  padding: 10px 12px;
}
.stat-label { font-size: 11px; color: #888; letter-spacing: 1px; }
.stat-value { font-size: 16px; color: #333; margin-top: 4px; font-weight: 600; }
.stat-value.ink { color: #2E8B57; }
.stat-value.gold { color: #b8860b; }
.peer-section { margin-top: 8px; }
.peer-head { font-size: 13px; color: #666; letter-spacing: 1px; margin-bottom: 8px; }
.peer-count {
  background: var(--seal);
  color: #fff;
  border-radius: 10px;
  padding: 1px 8px;
  font-size: 11px;
  margin-left: 6px;
}
.peer-list {
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid rgba(26, 26, 26, 0.08);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.6);
  padding: 6px 10px;
}
.peer-item { padding: 7px 2px; border-bottom: 1px dashed rgba(26, 26, 26, 0.08); }
.peer-ip {
  font-size: 13px;
  color: #333;
  font-family: 'Consolas', 'Courier New', monospace;
}
.peer-meta { font-size: 11px; color: #999; margin-top: 2px; }
.peer-empty { color: #aaa; font-size: 12px; text-align: center; padding: 12px; }
/* 简易使用提示(水墨风) */
.usage-tip {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  margin: 4px 0 16px;
  padding: 14px 18px;
  border: 1px dashed rgba(26, 26, 26, 0.18);
  border-radius: 6px;
  background: rgba(255, 252, 247, 0.55);
}
.usage-left { flex-shrink: 0; margin-top: 2px; }
.usage-body { flex: 1; }
.usage-title {
  font-size: 17px;
  color: #333;
  letter-spacing: 3px;
  margin-bottom: 8px;
}
.usage-steps {
  margin: 0;
  padding-left: 18px;
  color: #666;
  font-size: 13px;
  line-height: 2;
}
.usage-steps li b { color: #2E8B57; font-weight: 600; }
</style>
