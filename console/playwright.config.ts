import { defineConfig } from '@playwright/test';

// 控制台 E2E（V6 生产化模拟刀 C1）：把 runbook《控制台最小页.md》§6 的人工五步冒烟
// 自动化成真实浏览器用例（docs/runbook/Tracing与E2E.md §E2E）。
//
// 驱动方式（与"Playwright 自己起被测服务"的常见形态不同，这是刻意选择）：
// 服务端由 JUnit 集成测试 ConsoleE2eIntegrationTest 用 @SpringBootTest(RANDOM_PORT)
// + Testcontainers 真实起好，再 ProcessBuilder 拉起 `npx playwright test`——
// 本配置**不配 webServer**，目标地址与登录凭据全部经环境变量注入：
//   CONSOLE_BASE_URL        必填：被测控制台地址（JUnit 传 http://127.0.0.1:<随机端口>）
//   CONSOLE_ADMIN_USER      登录用户名（默认 admin——dev 种子管理员）
//   CONSOLE_ADMIN_PASSWORD  登录密码（dev 种子值，由 JUnit 传入，不落进仓库文件）
//   CONSOLE_TEMPLATE_NAME   预置模板名（JUnit 建好并传入；步骤三断言列表里能看到它）
//   CONSOLE_E2E_SHOT        可选：列表视图截图落盘路径（取证用）
export default defineConfig({
  testDir: './e2e',
  // 五步是一条业务流（登录态跨步骤传递的语义在用例内部自洽），单 worker 串行：
  // 并行只省几秒，却让"上一条用例登出影响下一条"这类耦合不可读。
  workers: 1,
  timeout: 30_000,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.CONSOLE_BASE_URL ?? 'http://127.0.0.1:8080',
    headless: true,               // WSL 无 X 也能跑；有头留给人工排查
    screenshot: 'only-on-failure',
    trace: 'off',                 // WSL 内存纪律：默认不开 trace 录制（~百 MB/用例）
  },
});
