import {defineConfig} from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
    plugins: [vue()],
    server: {
        proxy: {
            "/api": "http://localhost:9091"
        }
    },
    build: {
        outDir: "../src/main/resources/static",
        emptyOutDir: false
    }
});
