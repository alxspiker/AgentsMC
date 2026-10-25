import { defineConfig } from "vite";
import { viteSingleFile } from "vite-plugin-singlefile";

export default defineConfig({
  plugins: [viteSingleFile()],
  esbuild: { legalComments: "none" },
  build: {
    outDir: "../main/src/main/resources",
    emptyOutDir: false,
    assetsInlineLimit: 100000000,
    cssCodeSplit: false,
    minify: "terser",
    terserOptions: {
      compress: { passes: 3, drop_console: true, pure_getters: true, unsafe: true },
      format: { comments: false, quote_style: 1 },
    },
    rollupOptions: {
      input: { "agent-panel": "index.html" },
    },
  },
});
