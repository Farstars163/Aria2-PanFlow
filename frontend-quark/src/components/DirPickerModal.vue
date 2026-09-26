<script setup>
import { ref, watch } from 'vue'
import { fsApi } from '@/api/client'
import { toast } from '@/api/toast'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'select'])

const stack = ref([])       // 目录栈: { name, path }
const children = ref([])    // 当前目录子项
const loading = ref(false)
const manualPath = ref('')

const currentPath = () => {
  if (stack.value.length === 0) return ''
  return stack.value[stack.value.length - 1].path
}

async function load(path) {
  loading.value = true
  try {
    const list = await fsApi.list(path || '')
    children.value = list || []
  } catch (e) {
    toast.error(e.message)
    children.value = []
  } finally {
    loading.value = false
  }
}

function openDir(item) {
  stack.value.push({ name: item.name, path: item.path })
  load(item.path)
}

function goBack() {
  if (stack.value.length === 0) return
  stack.value.pop()
  load(currentPath())
}

function useManual() {
  const p = manualPath.value.trim()
  if (!p) return
  emit('select', p)
  close()
}

function confirmCurrent() {
  emit('select', currentPath())
  close()
}

function close() {
  emit('update:modelValue', false)
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      stack.value = []
      manualPath.value = ''
      load('')
    }
  }
)
</script>

<template>
  <div v-if="modelValue" class="overlay" @click.self="close">
    <div class="modal">
      <div class="modal-head">
        <div class="modal-title">
          <span>◉</span> 择一归处 · 下载目录
        </div>
        <button class="modal-close" @click="close">✕</button>
      </div>

      <div class="field">
        <label class="field-label">直接输入路径</label>
        <div class="field-row">
          <input v-model="manualPath" class="ink-input" placeholder="如 D:/Downloads 或 /Users/name/Downloads" @keyup.enter="useManual" />
          <button class="ink-btn ghost" @click="useManual">选用</button>
        </div>
      </div>

      <div class="field">
        <div class="field-label" style="display: flex; justify-content: space-between; align-items: center;">
          <span>浏览目录</span>
          <button v-if="stack.length > 0" class="ink-btn ghost small" @click="goBack">返回上级</button>
        </div>

        <div v-if="stack.length > 0" style="margin-bottom: 10px; font-size: 13px; color: var(--ink-淡);">
          当前: <span class="font-calligraphy-行" style="font-size: 15px; color: var(--ink-浓);">{{ currentPath() || '根目录' }}</span>
        </div>

        <div v-loading="loading" style="max-height: 260px; overflow-y: auto; border: 1px solid rgba(26,26,26,0.08); border-radius: 4px;">
          <div
            v-for="item in children"
            :key="item.path"
            class="dir-row"
            @click="openDir(item)"
          >
            <span class="dir-name"><span class="folder-mark">▣</span> {{ item.name }}</span>
            <span class="dir-meta">{{ item.hasChildren ? '含子目录' : '空' }}</span>
          </div>
          <div v-if="children.length === 0 && !loading" class="empty-ink" style="padding: 30px;">
            <div class="hint">此间无目录 · 留白亦风景</div>
          </div>
        </div>
      </div>

      <div class="modal-foot">
        <button class="ink-btn" @click="close">作罢</button>
        <button class="ink-btn seal" @click="confirmCurrent" :disabled="loading">
          择此目录
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.folder-mark { color: var(--gold); }
[v-loading] { position: relative; }
</style>
