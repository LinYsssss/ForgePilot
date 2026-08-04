import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 默认打到部署栈的后端;联调本地 dev 后端时用 VITE_PROXY_TARGET 覆盖
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
