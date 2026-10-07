import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// Portal dev server (issue #35): same-origin paths (`/api/...`) are
// proxied to the local backend container, exactly like the console —
// the API client needs no environment-specific base URL. Port 5174
// keeps the portal distinct from the console's 5173 both in dev and in
// compose (portal nginx container).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
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
          mui: ["@mui/material", "@mui/icons-material"],
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
