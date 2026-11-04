import { useCallback, useEffect, useState } from 'react';
import { api, onUnauthorized, AdminProfile } from './api';
import { LoginView } from './LoginView';
import { ListView } from './ListView';

// 两个视图按登录态切换（裁剪裁定：不引入路由库——一共两个屏，
// "登录与否"就是全部路由状态）。刷新页面后的恢复靠挂载时的 /me 探测。
export function App() {
  const [user, setUser] = useState<AdminProfile | null>(null);
  const [booting, setBooting] = useState(true);
  const [notice, setNotice] = useState('');

  const backToLogin = useCallback((msg: string) => {
    setUser(null);
    setNotice(msg);
  }, []);

  // 全局 401 拦截：会话过期/被强制下线时，任何请求都会把视图打回登录页
  useEffect(() => {
    onUnauthorized(() => backToLogin('会话已过期，请重新登录'));
  }, [backToLogin]);

  // 刷新恢复：有会话则直接进列表视图。探测 /me 的 401 走 unauthorizedIsError——
  // 它只说明"本来就没登录"（刷新/首开），不该触发全局 401 拦截的"会话已过期"提示，
  // 否则从未登录过的用户首开页面就看到红色错误（审查 mi1）
  useEffect(() => {
    api<AdminProfile>('/console/api/auth/me', undefined, { unauthorizedIsError: true })
      .then(setUser)
      .catch(() => undefined)
      .finally(() => setBooting(false));
  }, []);

  if (booting) {
    return <div className="center-note">加载中…</div>;
  }
  if (!user) {
    return <LoginView notice={notice} onLogin={setUser} />;
  }
  return <ListView user={user} onLogout={() => backToLogin('')} />;
}
