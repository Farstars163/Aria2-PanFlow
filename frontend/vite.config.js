import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 构建产物直接输出到 Spring Boot 静态资源目录, jar 打包后一站式托管前端
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true }
    }
  },
  build: {
    // 夸克试用版构建: QUARK_OUT 指定独立输出目录(如 ../../build-quark), 不影响全功能版输出
    outDir: process.env.QUARK_OUT ? process.env.QUARK_OUT : '../src/main/resources/static',
    emptyOutDir: true,
    assetsDir: 'assets'
  }
})
