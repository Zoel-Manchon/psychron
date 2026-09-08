import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dev server proxies /api to the backend so the browser sees one origin,
// exactly as it does behind Caddy in production. Without this the two setups
// differ in the one dimension most likely to break something — cross-origin
// requests, preflight and credentials — and the difference only shows up after
// deployment.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8000",
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
