import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// Dev server: same-origin paths (`/api/...`) are proxied to the local
// backend container, so the API client needs no environment-specific
// base URL. In docker compose the nginx frontend container proxies the
// same paths to the api service (see frontend/nginx.conf).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
      "/actuator": { target: "http://localhost:8081", changeOrigin: true },
    },
  },
  build: {
    outDir: "dist",
    rollupOptions: {
      output: {
        manualChunks: {
          react: ["react", "react-dom", "react-router-dom"],
          mui: ["@mui/material", "@mui/icons-material", "@mui/x-data-grid"],
        },
      },
    },
  },
  test: {
    environment: "happy-dom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
