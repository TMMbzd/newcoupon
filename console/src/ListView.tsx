import { useCallback, useEffect, useState } from 'react';
import { api, csrfHeader, UnauthorizedError, AdminProfile, TemplatePage } from './api';

const PAGE_SIZE = 20;

// 模板列表页：GET /api/templates（会话 Cookie 自动带）+ 上一页/下一页 + 登出。
// "已领"列是后端聚合好的分片真实口径 Σcoupon_stock_shard.used（issued_count 冻结列在 C1 下恒 0）。
export function ListView({ user, onLogout }: {
  user: AdminProfile;
  onLogout: () => void;
}) {
  const [data, setData] = useState<TemplatePage | null>(null);
  const [page, setPage] = useState(1);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(true);

  const load = useCallback(async (p: number) => {
    setBusy(true);
    setError('');
    try {
      const result = await api<TemplatePage>(`/api/templates?page=${p}&size=${PAGE_SIZE}`);
      setData(result);
      setPage(p);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败');
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    void load(1);
  }, [load]);

  // 登出的两种失败要分开（审查 mi2）：401 = 会话本来就没了（已等价登出，回登录页）；
  // 其它失败（网络断/服务 5xx）= 服务端会话还活着（最长空闲 30 分钟），
  // 回登录页等于把还活着的会话留在共用电脑上——留在本页提示重试
  async function logout() {
    setError('');
    try {
      const headers = {
        'Content-Type': 'application/json',
        ...(await csrfHeader()),
      };
      await api('/console/api/auth/logout', { method: 'POST', headers });
      onLogout();
    } catch (err) {
      if (err instanceof UnauthorizedError) {
        onLogout();
      } else {
        setError('登出失败，请检查网络后重试');
      }
    }
  }

  const rows = data?.rows ?? [];
  const totalPages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <div className="page">
      <header className="topbar">
        <h1>券模板</h1>
        <div className="who">
          <span>{user.displayName || user.username}</span>
          <button className="ghost" onClick={logout}>登出</button>
        </div>
      </header>

      {error && <div className="alert">{error}</div>}

      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>券类型</th>
            <th>面额</th>
            <th>状态</th>
            <th>总量</th>
            <th>已领</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((t) => (
            <tr key={t.id}>
              <td>{t.id}</td>
              <td>{t.name}</td>
              <td>{t.couponType}</td>
              <td>{t.faceValue}</td>
              <td>{t.publishStatus}</td>
              <td>{t.totalCount}</td>
              <td>{t.issuedCount}</td>
              <td>{t.createTime?.slice(0, 19).replace('T', ' ')}</td>
            </tr>
          ))}
          {!busy && rows.length === 0 && (
            <tr>
              <td colSpan={8} className="empty">本页没有数据</td>
            </tr>
          )}
        </tbody>
      </table>

      <footer className="pager">
        <span>共 {data?.total ?? 0} 条 · 第 {page}/{totalPages} 页</span>
        <div>
          <button disabled={busy || page <= 1} onClick={() => void load(page - 1)}>
            上一页
          </button>
          <button disabled={busy || page >= totalPages || rows.length === 0}
                  onClick={() => void load(page + 1)}>
            下一页
          </button>
        </div>
      </footer>
    </div>
  );
}
