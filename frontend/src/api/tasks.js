import { reactive } from 'vue'
import { wsUrl } from './client'

// 全局任务状态(由 WebSocket 实时驱动)
export const taskStore = reactive({
  wsConnected: false,
  active: [],
  waiting: [],
  complete: [],
  totalSpeed: '0'
})

let ws = null
let retryTimer = null

export function connectTasks() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return

  ws = new WebSocket(wsUrl())

  ws.onopen = () => {
    taskStore.wsConnected = true
  }

  ws.onclose = () => {
    taskStore.wsConnected = false
    scheduleReconnect()
  }

  ws.onerror = () => {
    try { ws.close() } catch (e) { /* ignore */ }
  }

  ws.onmessage = (ev) => {
    try {
      const data = JSON.parse(ev.data)
      const r = data.result
      if (!r) return
      taskStore.active = Array.isArray(r.active) ? r.active : []
      taskStore.waiting = Array.isArray(r.waiting) ? r.waiting : []
      taskStore.complete = Array.isArray(r.complete) ? r.complete : []
      taskStore.totalSpeed = r.totalSpeed || '0'
    } catch (e) { /* 忽略脏数据 */ }
  }
}

function scheduleReconnect() {
  clearTimeout(retryTimer)
  retryTimer = setTimeout(() => connectTasks(), 3000)
}
