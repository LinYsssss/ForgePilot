import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需导入:模板里用到的 el-* 组件自动注入 import 与样式。
    // 不加 vue 的 imports 预设(项目风格是显式 import);不产 dts(纯 JS 工程)。
    AutoImport({ resolvers: [ElementPlusResolver()], dts: false }),
    Components({ resolvers: [ElementPlusResolver()], dts: false })
  ],
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
