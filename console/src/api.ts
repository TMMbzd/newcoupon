// 统一 HTTP 出口（唯一封装点，两个视图都只经这里发请求）。
// 三个语义：
//   ① 会话 Cookie 自动带——same-origin 是 fetch 默认值，显式写出是给读代码的人看；
//   ② 401 → 调全局 onUnauthorized 回调（App 切回登录视图）并抛 UnauthorizedError；
//      例外：登录接口自身的 401 是"用户名或密码错误/锁定"的业务答复（文案在响应体），
//      传 unauthorizedIsError 让它走普通错误文案，不触发回登录的跳转；
//   ③ Result.code ≠ 0 → 抛 Error(message)——锁定提示、参数错误等文案直接来自后端。
export class UnauthorizedError extends Error {}

let unauthorizedHandler: (() => void) | null = null;

/** App 启动时注册：任何请求 401 都回到登录视图（会话过期/被强制下线）。 */
export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler;
}

export async function api<T = unknown>(
  path: string,
  init?: RequestInit,
  opts?: { unauthorizedIsError?: boolean },
): Promise<T> {
  const resp = await fetch(path, { credentials: 'same-origin', ...init });
  if (resp.status === 401) {
    const body = await resp.json().catch(() => ({ message: '' }));
    if (opts?.unauthorizedIsError) {
      throw new Error(body.message || '未登录或会话已过期');
    }
    unauthorizedHandler?.();
    throw new UnauthorizedError('未登录或会话已过期');
  }
  const body = await resp.json().catch(() => ({ code: -1, message: '响应不是合法 JSON' }));
  if (body.code !== 0) {
    throw new Error(body.message ?? '请求失败');
  }
  return body.data as T;
}

// CSRF：后端用 Cookie 仓（httpOnly=false，JS 可读）+ X-CSRF-TOKEN 头回传。
// 发写请求（登录/登出）前先 GET 一次 /console/api 端点领 Cookie——幂等，
// CsrfFilter 对 GET 不校验只发令牌（这正是 Spring Security 的 SPA 配方）。
export async function csrfHeader(): Promise<Record<string, string>> {
  await fetch('/console/api/auth/me', { credentials: 'same-origin' });
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? { 'X-CSRF-TOKEN': decodeURIComponent(m[1]) } : {};
}

// ---- 与后端契约对应的类型（手写最小集，未引入 openapi-typescript 类型生成） ----

/** 登录/me 响应（AdminSessionService.AdminProfile） */
export interface AdminProfile {
  userId: number;
  username: string;
  displayName: string | null;
  roles: string[];
  permissions: string[];
}

/** 模板列表一行（TemplateQueryDtos.TemplateListItem） */
export interface TemplateRow {
  id: number;
  name: string;
  couponType: string;
  faceValue: number;
  publishStatus: string;
  totalCount: number;
  issuedCount: number;
  createTime: string;
}

/** 分页结果（TemplateQueryDtos.TemplatePage） */
export interface TemplatePage {
  total: number;
  page: number;
  size: number;
  rows: TemplateRow[];
}
