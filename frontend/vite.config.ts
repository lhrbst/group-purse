/// <reference types="vitest/config" />
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

/** Vite 8 beendet unter Windows den Prozess nach dem Build nicht von selbst; auf Linux (Docker, CI) ist das nicht nötig. */
function exitAfterBuildOnWindows(): Plugin {
  return {
    name: 'exit-after-build-on-windows',
    apply: 'build',
    closeBundle: {
      order: 'post',
      handler() {
        if (process.platform === 'win32') setTimeout(() => process.exit(0), 3000).unref();
      },
    },
  };
}

export default defineConfig({
  plugins: [
    react(),
    exitAfterBuildOnWindows(),
    ...(process.env.VITEST ? [] : [VitePWA({
      strategies: 'injectManifest',
      srcDir: 'src',
      filename: 'sw.ts',
      registerType: 'autoUpdate',
      injectRegister: 'auto',
      manifest: {
        name: 'Gruppenkasse',
        short_name: 'Gruppenkasse',
        description: 'Gruppenkasse für Wohngruppen: Belege erfassen, Kasse und Elternbeiträge im Blick',
        lang: 'de',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'portrait',
        background_color: '#ffffff',
        theme_color: '#1c7ed6',
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      injectManifest: { globPatterns: ['**/*.{js,css,html,svg,png,woff2}'] },
      devOptions: { enabled: false },
    })]),
  ],
  server: {
    proxy: { '/api': 'http://localhost:8080', '/actuator': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
