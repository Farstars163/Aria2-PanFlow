<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import QRCode from 'qrcode'
import { panServiceApi } from '@/api/client'
import { toast } from '@/api/toast'
import SealStamp from '@/components/SealStamp.vue'

const props = defineProps({
  service: { type: String, required: true }, // pan123 | aliyun
  name: { type: String, required: true }
})

const emit = defineEmits(['started', 'stopped'])

const status = ref(null)
const loading = ref(false)
let wasRunning = false   // 记录上次运行状态, 检测"等待扫码→运行中"转变(扫码完成)
const qr = ref(null)          // 二维码数据 (aliyun: {base64}; pan123: {qrText})
const qrImage = ref('')       // 渲染用的图片 src
const qrMsg = ref('')         // 轮询提示文案
const qrPhase = ref('')       // scanning | confirm | expired | done
const showQr = ref(false)
const pollTimer = ref(null)
const qrTimer = ref(null)
const uniID = ref('')

/* ---------------- 状态 ---------------- */

async function refreshStatus() {
  try {
    status.value = await panServiceApi.status(props.service)
    if (status.value?.running) {
      stopQrPolling()
      showQr.value = false
      // 扫码授权完成后(从非运行→运行)触发父组件重新加载目录
      if (!wasRunning) {
        wasRunning = true
        emit('started')
      }
    } else {
      wasRunning = false
    }
  } catch (e) {
    /* 后端不可用时静默 */
  }
}

/* ---------------- 阿里云盘: 轮询 TEMP 图片 ---------------- */

async function loadAliyunQr() {
  try {
    const data = await panServiceApi.qrcode('aliyun')
    if (data && data.available && data.base64) {
      qrImage.value = `data:${data.mime || 'image/png'};base64,${data.base64}`
      qrMsg.value = '请使用 阿里云盘 App 扫描二维码授权'
      qrPhase.value = 'scanning'
    } else {
      qrMsg.value = data?.msg || '二维码生成中…'
    }
  } catch (e) {
    /* 网络抖动忽略 */
  }
}

/* ---------------- 123云盘: 官方 API 扫码登录 ---------------- */

async function startPan123Scan() {
  try {
    const data = await panServiceApi.pan123QrGenerate()
    uniID.value = data.uniID
    const text = data.qrText
    // 前端生成二维码图片, 网页内直接展示
    qrImage.value = await QRCode.toDataURL(text, { width: 220, margin: 1 })
    qrPhase.value = 'scanning'
    qrMsg.value = '请使用 123云盘 App / 微信 扫描二维码授权'
    showQr.value = true
    qrTimer.value = setInterval(pollPan123Status, 2000)
  } catch (e) {
    toast.error(e.message)
    showQr.value = false
  }
}

async function pollPan123Status() {
  if (!uniID.value) return
  try {
    const st = await panServiceApi.pan123QrStatus(uniID.value)
    const ls = st.loginStatus
    if (ls === 0) {
      qrPhase.value = 'scanning'
      qrMsg.value = '请使用 123云盘 App / 微信 扫描二维码授权'
    } else if (ls === 1) {
      qrPhase.value = 'confirm'
      qrMsg.value = '已扫码！请在手机上点击【确认】…'
    } else if (ls === 4) {
      qrPhase.value = 'expired'
      qrMsg.value = '二维码已过期，请重新点击「启动服务」'
      stopQrPolling()
    } else {
      // 确认成功: 换 token 并自动启动服务
      qrPhase.value = 'done'
      qrMsg.value = '扫码确认成功，正在换取 Token…'
      stopQrPolling()
      await finishPan123Login()
    }
  } catch (e) {
    /* 网络抖动忽略 */
  }
}

async function finishPan123Login() {
  try {
    const res = await panServiceApi.pan123QrConfirm(uniID.value)
    toast.success(res?.startMsg || '123云盘授权成功，服务已启动')
    showQr.value = false
    uniID.value = ''
    emit('started')
    setTimeout(refreshStatus, 1500)
  } catch (e) {
    toast.error(e.message)
    qrPhase.value = 'scanning'
    qrMsg.value = '登录确认失败，可重新扫码'
    showQr.value = false
  }
}

/* ---------------- 启动 / 停止 ---------------- */

async function start() {
  loading.value = true
  try {
    if (props.service === 'pan123') {
      // 123: 有有效 token 直接启动; 无 token 或 token 失效走网页内扫码
      const tok = await panServiceApi.pan123TokenStatus()
      if (tok?.tokenExists) {
        const res = await panServiceApi.start('pan123')
        if (res?.loginRequired) {
          // token 已失效(后端验证 401): 走重新扫码
          await startPan123Scan()
        } else {
          toast.success(res?.msg || '123云盘服务已启动')
          emit('started')
          setTimeout(refreshStatus, 1500)
        }
      } else {
        await startPan123Scan()
      }
    } else {
      // 阿里: 先检查有效 token → 有则直接启动(不生成二维码, 直接显示已授权); 无则生成二维码
      const st = await panServiceApi.status('aliyun')
      const hasToken = !!(st && st.tokenExists)
      const res = await panServiceApi.start('aliyun')
      if (hasToken) {
        // 已有本地登录凭证: 不提示扫码, 与后端同步
        toast.success('阿里云盘服务已启动（已读取本地登录凭证）')
      } else {
        toast.success(res?.msg || `${props.name}服务已启动`)
      }
      if (res?.success && !hasToken) {
        showQr.value = true
        qrPhase.value = 'scanning'
        qrMsg.value = '二维码生成中…'
        loadAliyunQr()
        qrTimer.value = setInterval(loadAliyunQr, 2500)
      } else if (res?.success && hasToken) {
        // 已有有效 token: 不生成二维码, 直接进入已授权状态
        showQr.value = false
        qrMsg.value = '已检测到本地授权，服务运行中'
      }
      setTimeout(refreshStatus, 1500)
    }
  } catch (e) {
    toast.error(e.message)
  } finally {
    loading.value = false
  }
}

async function stop() {
  loading.value = true
  try {
    const res = await panServiceApi.stop(props.service)
    toast.info(res?.msg || `${props.name}服务已停止`)
    stopQrPolling()
    showQr.value = false
    emit('stopped')          // 通知父组件清空列表
    refreshStatus()
  } catch (e) {
    toast.error(e.message)
  } finally {
    loading.value = false
  }
}

function stopQrPolling() {
  if (qrTimer.value) {
    clearInterval(qrTimer.value)
    qrTimer.value = null
  }
}

onMounted(() => {
  refreshStatus()
  pollTimer.value = setInterval(refreshStatus, 5000)
})

onUnmounted(() => {
  clearInterval(pollTimer.value)
  stopQrPolling()
})
</script>

<template>
  <div class="credential-card pan-service-panel">
    <div class="service-head">
      <SealStamp :text="service === 'pan123' ? '壹' : '阿'" :size="44" />
      <div class="service-info">
        <div class="service-title font-calligraphy-行">{{ name }}服务</div>
        <div class="service-state" :class="status?.running ? 'on' : (status?.processAlive ? 'auth' : 'off')">
          <span class="state-dot"></span>
          <span class="font-body-楷">
            {{ status?.running ? '服务运行中 · 授权完成' : (status?.processAlive ? '等待扫码授权…' : '默认未启动') }}
          </span>
        </div>
      </div>
    </div>

    <div class="tip" style="margin-top: 12px;">
      {{ service === 'pan123'
        ? '123云盘首次使用扫码登录，后续可自动读取登录凭证。'
        : '阿里云盘首次使用扫码登录，后续自动读取登录凭证。' }}
    </div>

    <!-- 网页内扫码区域 -->
    <div v-if="showQr" class="qr-panel">
      <div class="qr-frame">
        <img v-if="qrImage" :src="qrImage" class="qr-img" alt="扫码授权" />
        <div v-else class="qr-wait font-body-楷">
          <span class="breathing">◌</span> 二维码生成中…
        </div>
      </div>

      <div class="qr-tip font-body-楷" :class="'phase-' + qrPhase">
        {{ qrMsg }}
        <span v-if="qrPhase === 'expired'" class="qr-retry">（重新点击「启动服务」即可刷新）</span>
      </div>
    </div>

    <div class="field-row" style="margin-top: 16px;">
      <button class="ink-btn seal" :disabled="loading || status?.running" @click="start">
        {{ loading ? '落墨中…' : (status?.running ? '运行中' : (qrPhase === 'expired' ? '重新扫码' : '启动服务')) }}
      </button>
      <button v-if="status?.running || status?.processAlive" class="ink-btn ghost" :disabled="loading" @click="stop">停止服务</button>
    </div>
  </div>
</template>

<style scoped>
.pan-service-panel { border-left: 3px solid var(--gold); }
.service-head { display: flex; align-items: center; gap: 14px; }
.service-title { font-size: 22px; color: var(--ink-焦); letter-spacing: 2px; }
.service-state { margin-top: 4px; display: flex; align-items: center; gap: 8px; font-size: 13px; }
.state-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--ink-清); box-shadow: 0 0 6px var(--ink-清);
}
.service-state.on .state-dot { background: var(--jade); box-shadow: 0 0 8px var(--jade); }
.service-state.on { color: var(--jade); }
.service-state.auth .state-dot { background: var(--gold); box-shadow: 0 0 8px var(--gold); }
.service-state.auth { color: var(--gold); }
.service-state.off { color: var(--ink-清); }

.qr-panel {
  margin-top: 16px;
  padding: 18px;
  background: rgba(255, 252, 247, 0.65);
  border: 1px dashed rgba(26, 26, 26, 0.18);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.qr-frame {
  width: 230px; height: 230px;
  display: flex; align-items: center; justify-content: center;
  background: #fff;
  border: 1px solid rgba(26, 26, 26, 0.12);
  box-shadow: var(--shadow-ink-sm);
}
.qr-img { width: 220px; height: 220px; image-rendering: pixelated; }
.qr-wait { color: var(--ink-淡); font-size: 14px; }
.qr-tip { font-size: 13px; color: var(--jade); letter-spacing: 1px; }
.qr-tip.phase-confirm { color: var(--gold); }
.qr-tip.phase-expired { color: var(--seal); }
.qr-retry { color: var(--ink-清); font-size: 12px; }
</style>
