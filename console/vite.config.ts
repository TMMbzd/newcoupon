import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 构建产物直接写进 Spring Boot 的 classpath 静态目录（产物入 git，克隆即跑——
// 不强制要求部署环境装 node）。emptyOutDir=true 只清空 outDir 自身
// （static/console），不会碰 static/ 下其它内容；outDir 在项目根之外时 vite
// 默认不敢清，这里显式声明"这个目录归我管，可清"。
export default defineConfig({
  // base 必须带 /console/：页面由 classpath static/console 托管在 /console/ 路径下，
  // 默认的 '/' 会让构建产物引用 /assets/...（404）
  base: '/console/',
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static/console',
    emptyOutDir: true,
  },
});
