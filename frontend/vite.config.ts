import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import cesium from 'vite-plugin-cesium'

export default defineConfig({
  // vite-plugin-cesium 负责拷贝 Assets/Workers/ThirdParty 并注入 CESIUM_BASE_URL
  plugins: [vue(), cesium()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 后端固定跑在 8080，前端一律用相对路径 /api，避免跨域与硬编码地址
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 实时推送通道，ws: true 才会转发 Upgrade 头完成握手
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true
      }
    }
  }
})
