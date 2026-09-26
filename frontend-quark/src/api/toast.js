import { reactive } from 'vue'

// 极简墨迹提示
export const toasts = reactive({ list: [] })

let seq = 0

function push(type, text) {
  const id = ++seq
  toasts.list.push({ id, type, text })
  setTimeout(() => {
    const i = toasts.list.findIndex((t) => t.id === id)
    if (i >= 0) toasts.list.splice(i, 1)
  }, 3200)
}

export const toast = {
  success: (text) => push('success', text),
  error: (text) => push('error', text),
  info: (text) => push('info', text)
}
