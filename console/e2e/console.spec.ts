import { expect, test } from '@playwright/test';

// 人工五步的自动化（runbook《控制台最小页.md》§6 一一对应）：
//   ① 打开 /console/ 出登录页，且**没有**"会话已过期"弹窗（mi1 回归：首开 /me 的 401
//      不走全局拦截，未登录用户不该看到红色错误）
//   ② 错误密码 → 统一文案"用户名或密码错误"（7011 不区分用户名存在与否——防枚举）
//   ③ 正确登录 → 模板列表出现预置模板、右上角显示用户名、首页"上一页"禁用（§6 第 4 步）
//   ④ 登出 → 回登录视图
//   ⑤ 登出后刷新 → 仍停留登录页（会话已亡；mi2 回归：不把死会话误判成"还登录着"）
//
// 每条用例拿独立浏览器上下文（Cookie 隔离），需要登录态的用例自己从登录走起——
// 不依赖用例间顺序，单独 -g 跑任意一条都成立。

const ADMIN_USER = process.env.CONSOLE_ADMIN_USER ?? 'admin';
const ADMIN_PASSWORD = process.env.CONSOLE_ADMIN_PASSWORD ?? '';
const TEMPLATE_NAME = process.env.CONSOLE_TEMPLATE_NAME ?? '';
const SHOT_PATH = process.env.CONSOLE_E2E_SHOT ?? '';

/** 走真实 UI 登录（不是绕过前端直接调接口——E2E 验的就是这条链） */
async function login(page: import('@playwright/test').Page, password: string) {
  await page.goto('/console/');
  await page.getByLabel('用户名').fill(ADMIN_USER);
  await page.getByLabel('密码').fill(password);
  await page.getByRole('button', { name: '登录' }).click();
}

test.beforeAll(() => {
  // 凭据缺失时立刻给出可读错误，而不是让每条用例各自以"登录失败"红掉
  if (!ADMIN_PASSWORD) throw new Error('缺 CONSOLE_ADMIN_PASSWORD（JUnit 应注入种子管理员密码）');
  if (!TEMPLATE_NAME) throw new Error('缺 CONSOLE_TEMPLATE_NAME（JUnit 应先建好预置模板）');
});

test('① 打开 /console/ 出登录页，且无"会话已过期"弹窗', async ({ page }) => {
  await page.goto('/console/');
  await expect(page.getByRole('heading', { name: 'newcoupon 管理台' })).toBeVisible();
  // mi1 回归点：从未登录用户的首开不该出现错误提示（/me 的 401 是探测，不是过期）
  await expect(page.locator('.alert')).toHaveCount(0);
});

test('② 错误密码 → 提示"用户名或密码错误"', async ({ page }) => {
  await login(page, 'definitely-wrong-password');
  await expect(page.locator('.alert')).toContainText('用户名或密码错误');
  // 停在登录页（没有因这次 401 被误判成"会话过期"跳转出别的东西）
  await expect(page.getByRole('heading', { name: 'newcoupon 管理台' })).toBeVisible();
});

test('③ 正确登录 → 列表见预置模板、右上角用户名、首页上一页禁用', async ({ page }) => {
  await login(page, ADMIN_PASSWORD);
  await expect(page.getByRole('heading', { name: '券模板' })).toBeVisible();
  // 预置模板由 JUnit 用真实 API 建好（id 倒序必在第一页）
  await expect(page.getByRole('cell', { name: TEMPLATE_NAME })).toBeVisible();
  // 右上角显示的是 displayName || username（ListView 契约）——种子管理员的
  // displayName 固定为"平台管理员（种子）"（AdminSeedInitializer）
  await expect(page.locator('.who')).toContainText('平台管理员（种子）');
  // §6 第 4 步：首页"上一页"禁用（分页边界态，纯前端逻辑——集成测试够不到这里）
  await expect(page.getByRole('button', { name: '上一页' })).toBeDisabled();
  if (SHOT_PATH) {
    await page.screenshot({ path: SHOT_PATH, fullPage: true });
  }
});

test('④ 登出 → 回登录视图', async ({ page }) => {
  await login(page, ADMIN_PASSWORD);
  await expect(page.getByRole('heading', { name: '券模板' })).toBeVisible();
  await page.getByRole('button', { name: '登出' }).click();
  await expect(page.getByRole('heading', { name: 'newcoupon 管理台' })).toBeVisible();
});

test('⑤ 登出后刷新 → 仍停留登录页（会话已亡）', async ({ page }) => {
  await login(page, ADMIN_PASSWORD);
  await expect(page.getByRole('heading', { name: '券模板' })).toBeVisible();
  await page.getByRole('button', { name: '登出' }).click();
  await expect(page.getByRole('heading', { name: 'newcoupon 管理台' })).toBeVisible();
  // mi1/mi2 的回归价值兑现点：登出=会话在服务端已亡，刷新靠 /me 探测恢复登录态，
  // 死会话必须探成"未登录"——刷新后仍是登录页，且不是"会话已过期"的错误态
  await page.reload();
  await expect(page.getByRole('heading', { name: 'newcoupon 管理台' })).toBeVisible();
  await expect(page.locator('.alert')).toHaveCount(0);
});
