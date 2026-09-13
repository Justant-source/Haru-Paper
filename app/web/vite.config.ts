import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'

// docs/app/web.md 3, 4절: 개발 시 /api는 M2 서버로 프록시, 프로덕션은 haru-web(nginx)이 같은 origin으로 프록시한다.
// 코드에서 API 주소를 하드코딩하지 않고 항상 상대 경로 /api/...를 쓴다.
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      // /api/* 응답은 캐시하지 않는다(예약·이력·기기 상태는 항상 최신이어야 함, web.md 4절)
      workbox: {
        navigateFallbackDenylist: [/^\/api\//],
        runtimeCaching: [],
      },
      manifest: {
        name: '하루종이',
        short_name: '하루종이',
        lang: 'ko',
        display: 'standalone',
        start_url: '/',
        background_color: '#ffffff',
        theme_color: '#111111',
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' },
        ],
      },
    }),
  ],
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true,
      },
    },
  },
})
