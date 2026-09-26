import { createRouter, createWebHashHistory } from 'vue-router'

const allRoutes = [
  { path: '/', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '下载管理' } },
  { path: '/cloud/baidu', name: 'baidu', component: () => import('@/views/BaiduView.vue'), meta: { title: '百度网盘' } },
  { path: '/cloud/quark', name: 'quark', component: () => import('@/views/QuarkView.vue'), meta: { title: '夸克网盘' } },
  { path: '/cloud/uc', name: 'uc', component: () => import('@/views/UcView.vue'), meta: { title: 'UC网盘' } },
  { path: '/cloud/mcloud', name: 'mcloud', component: () => import('@/views/McloudView.vue'), meta: { title: '中国移动云盘' } },
  { path: '/cloud/xunlei', name: 'xunlei', component: () => import('@/views/XunleiView.vue'), meta: { title: '迅雷云盘' } },
  { path: '/cloud/pan123', name: 'pan123', component: () => import('@/views/Pan123View.vue'), meta: { title: '123云盘' } },
  { path: '/cloud/aliyun', name: 'aliyun', component: () => import('@/views/AliyunView.vue'), meta: { title: '阿里云盘' } }
]

// 夸克试用版构建: VITE_QUARK_ONLY=1 时仅保留夸克页面, 全功能构建不受影响
const routes = import.meta.env.VITE_QUARK_ONLY === '1'
  ? allRoutes.filter((r) => r.path === '/' || r.path === '/cloud/quark')
  : allRoutes

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.afterEach((to) => {
  document.title = `${to.meta.title || 'PanDownloader'} · PanDownloader`
})

export default router
