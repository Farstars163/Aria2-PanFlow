<script setup>
import { ref, onMounted } from 'vue'
import { panServiceApi } from '@/api/client'

// 快捷授权入口: 仅展示当前页面所属网盘(web 类型)的授权入口.
// props.current = 当前网盘 key (baidu | quark), 只显示对应项.
const props = defineProps({
  current: { type: String, default: '' }
})

const links = ref([])

onMounted(async () => {
  try {
    const data = await panServiceApi.authLinks()
    const all = data?.links || {}
    links.value = Object.entries(all)
      .filter(([key, lk]) => lk.authType === 'web' && (!props.current || key === props.current))
      .map(([key, lk]) => ({ key, ...lk }))
  } catch (e) {
    /* ignore */
  }
})

function openUrl(url) {
  window.open(url, '_blank')
}
</script>

<template>
  <div class="auth-links">
    <div class="auth-link-row" v-for="lk in links" :key="lk.key">
      <span class="link-badge">{{ lk.title }}</span>
      <span class="link-desc font-body-楷">{{ lk.desc }}</span>
      <button class="ink-btn ghost small" @click="openUrl(lk.url)">打开授权 ↗</button>
    </div>
  </div>
</template>

<style scoped>
.auth-links { display: flex; flex-direction: column; gap: 8px; }
.auth-link-row {
  display: flex; align-items: center; gap: 12px;
  padding: 8px 12px;
  border: 1px dashed rgba(26, 26, 26, 0.15);
  border-radius: 4px;
  background: rgba(255, 252, 247, 0.5);
}
.link-badge {
  flex-shrink: 0;
  padding: 2px 10px;
  font-size: 12px;
  border: 1px solid var(--seal);
  color: var(--seal);
  border-radius: 20px;
  letter-spacing: 1px;
}
.link-desc { flex: 1; font-size: 13px; color: var(--ink-淡); }
</style>
