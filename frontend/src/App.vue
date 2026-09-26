<script setup>
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { taskStore, connectTasks } from '@/api/tasks'
import SealStamp from '@/components/SealStamp.vue'
import InkToast from '@/components/InkToast.vue'

const route = useRoute()

const allNavs = [
  { path: '/', title: '下载管理' },
  { path: '/cloud/baidu', title: '百度网盘' },
  { path: '/cloud/quark', title: '夸克网盘' },
  { path: '/cloud/uc', title: 'UC网盘' },
  { path: '/cloud/mcloud', title: '中国移动云盘' },
  { path: '/cloud/xunlei', title: '迅雷云盘' },
  { path: '/cloud/pan123', title: '123云盘' },
  { path: '/cloud/aliyun', title: '阿里云盘' }
]

// 夸克试用版构建: VITE_QUARK_ONLY=1 时导航只留夸克, 全功能构建不受影响
const navs = import.meta.env.VITE_QUARK_ONLY === '1'
  ? allNavs.filter((n) => n.path === '/' || n.path === '/cloud/quark')
  : allNavs

onMounted(() => {
  connectTasks()
  // 页面打开: 通知后端重置关闭标记(供启动器感知)
  fetch('/api/lifecycle/page-open', { method: 'POST' }).catch(() => {})
  // 页面关闭: 上报, 启动器据此自动停止服务
  window.addEventListener('beforeunload', () => {
    navigator.sendBeacon('/api/lifecycle/page-closed', new Blob(['close'], { type: 'text/plain' }))
  })
})
</script>

<template>
  <div class="app-shell">
    <div class="ink-landscape" aria-hidden="true"></div>

    <!-- 左侧墨栏 -->
    <aside class="sidebar">
      <div class="sidebar-top">
        <div class="logo">PanDownloader</div>
        <div class="logo-sub">PANDOWNLOADER</div>
        <div class="logo-line"></div>
      </div>

      <nav class="sidebar-nav">
        <router-link
          v-for="item in navs"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: route.path === item.path }"
        >
          <span class="nav-text font-calligraphy-行">{{ item.title }}</span>
        </router-link>
      </nav>

      <div class="sidebar-bottom">
        <div class="ws-indicator" :class="{ on: taskStore.wsConnected }">
          <span class="ws-dot"></span>
          <span class="font-body-楷">{{ taskStore.wsConnected ? '墨脉已通 · 实时推送' : '墨脉未连 · 待重连' }}</span>
        </div>
        <div class="sidebar-seal">
          <SealStamp :text="'舟'" :size="46" shape="round" />
        </div>
      </div>
    </aside>

    <!-- 主区域 -->
    <main class="main">
      <router-view />
    </main>

    <InkToast />
  </div>
</template>
