import { FormEvent, useState } from 'react';
import { api, csrfHeader, AdminProfile } from './api';

// 登录页：先领 CSRF Cookie（csrfHeader 内部的 GET），再 POST login。
// 失败文案（7011 用户名或密码错误/锁定提示）来自响应 message，前端不猜原因。
export function LoginView({ notice, onLogin }: {
  notice: string;
  onLogin: (profile: AdminProfile) => void;
}) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(notice);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const headers = {
        'Content-Type': 'application/json',
        ...(await csrfHeader()),
      };
      const profile = await api<AdminProfile>('/console/api/auth/login', {
        method: 'POST',
        headers,
        body: JSON.stringify({ username: username.trim(), password }),
      }, { unauthorizedIsError: true });
      onLogin(profile);
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <h1>newcoupon 管理台</h1>
        {error && <div className="alert">{error}</div>}
        <label>
          用户名
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
          />
        </label>
        <label>
          密码
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </label>
        <button type="submit" disabled={busy || !username || !password}>
          {busy ? '登录中…' : '登录'}
        </button>
      </form>
    </div>
  );
}
