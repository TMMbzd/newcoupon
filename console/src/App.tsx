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

  // 刷新恢复：有会话则直接进列表视图（me 是 GET，未登录 401 被吞——留在登录视图）
  useEffect(() => {
    api<AdminProfile>('/console/api/auth/me')
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
