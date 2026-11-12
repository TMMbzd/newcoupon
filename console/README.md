# console/ —— 管理台前端 + E2E

Vite + React 18 + TS 最小工程（登录 + 模板列表，V6 2D 裁剪版，形态裁定见
docs/runbook/控制台最小页.md）。本目录还承载控制台的浏览器 E2E（生产化模拟刀 C1）。

## 构建与 E2E 一图流

```bash
npm install --no-fund --no-audit   # 首次（esbuild 的 install 脚本已批进 package.json）
npm run build                      # tsc + vite build → 产物直写 ../src/main/resources/static/console/
npx playwright install chromium    # E2E 首次前置：装浏览器（~170MB 下载；node_modules 不含浏览器）
```

E2E **不单独手跑**：由 JUnit 集成测试 `ConsoleE2eIntegrationTest` 驱动（它起真实
Spring 上下文 + Testcontainers，再拉起 `npx playwright test`），默认被 surefire
排除、CI 不跑，手动触发命令与环境变量说明见 **docs/runbook/Tracing与E2E.md**。

目录：`src/` 前端源码；`e2e/console.spec.ts` 五步用例（runbook §6 的自动化）；
`playwright.config.ts`（不配 webServer——服务端由 JUnit 起，地址/凭据走环境变量）。
