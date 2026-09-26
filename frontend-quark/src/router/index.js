import { createRouter, createWebHashHistory } from 'vue-router'

// 夸克试用版: 仅保留下载管理 + 夸克网盘页面
const routes = [
  { path: '/', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '下载管理' } },
  { path: '/cloud/quark', name: 'quark', component: () => import('@/views/QuarkView.vue'), meta: { title: '夸克网盘' } }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.afterEach((to) => {
  document.title = `${to.meta.title || 'PanDownloader'} · PanDownloader`
})

export default router
