import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  base: "/GymApp/",
  define: {
    __APP_VERSION__: JSON.stringify(
      process.env.npm_package_version ?? "0.2.0-beta.1"
    )
  },
  plugins: [
    react(),
    VitePWA({
      strategies: "injectManifest",
      srcDir: "src",
      filename: "sw.ts",
      registerType: "prompt",
      includeAssets: ["icons/icon.svg", "icons/icon-192.png", "icons/icon-512.png"],
      manifest: {
        name: "GymApp",
        short_name: "GymApp",
        description: "Offline-first workout planning and training log.",
        theme_color: "#101317",
        background_color: "#101317",
        display: "standalone",
        start_url: "/GymApp/#/workouts",
        scope: "/GymApp/",
        orientation: "portrait-primary",
        icons: [
          {
            src: "icons/icon-192.png",
            sizes: "192x192",
            type: "image/png",
            purpose: "any"
          },
          {
            src: "icons/icon-512.png",
            sizes: "512x512",
            type: "image/png",
            purpose: "any"
          },
          {
            src: "icons/icon-maskable-512.png",
            sizes: "512x512",
            type: "image/png",
            purpose: "maskable"
          }
        ]
      },
      injectManifest: {
        globPatterns: ["**/*.{js,css,html,svg,json,webp,png,jpg}"],
        maximumFileSizeToCacheInBytes: 8 * 1024 * 1024
      }
    })
  ],
  test: {
    include: ["src/**/*.test.{ts,tsx}"],
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    css: true
  }
});
