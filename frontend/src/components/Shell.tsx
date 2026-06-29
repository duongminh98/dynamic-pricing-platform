import { ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useApi, useInterval } from '../lib/format';
import ErrorBoundary from './ErrorBoundary';

interface NavItem {
  to: string;
  label: string;
  ico: string;
}

const CUSTOMER_NAV: NavItem[] = [
  { to: '/products', label: 'Sản phẩm', ico: '◈' },
  { to: '/quote', label: 'Báo giá', ico: '◷' },
  { to: '/orders', label: 'Đơn hàng', ico: '▤' },
  { to: '/policies', label: 'Hợp đồng', ico: '⛨' },
  { to: '/claims', label: 'Bồi thường', ico: '✚' },
  { to: '/profile', label: 'Hồ sơ', ico: '◐' },
];

const ADMIN_NAV: NavItem[] = [
  { to: '/admin', label: 'Tổng quan', ico: '◰' },
  { to: '/admin/orders', label: 'Duyệt đơn', ico: '▤' },
  { to: '/admin/endorsements', label: 'Sửa đổi', ico: '✎' },
  { to: '/admin/claims', label: 'Bồi thường', ico: '✚' },
  { to: '/admin/policies', label: 'Hợp đồng', ico: '⛨' },
  { to: '/admin/billing', label: 'Hóa đơn', ico: '₫' },
  { to: '/admin/refunds', label: 'Hoàn tiền', ico: '↺' },
  { to: '/admin/customers', label: 'Khách hàng', ico: '◐' },
  { to: '/admin/catalog', label: 'Danh mục & giá', ico: '⚙' },
  { to: '/admin/models', label: 'Mô hình', ico: '◧' },
];

function Bell() {
  const { data, reload } = useApi<{ unreadCount: number }>('/notifications/unread-count');
  useInterval(reload, 20000);
  const count = data?.unreadCount ?? 0;
  return (
    <NavLink to="/notifications" className="bell" aria-label={`Thông báo${count ? `, ${count} chưa đọc` : ''}`}>
      <span>♪</span>
      {count > 0 && <span className="bell-badge">{count > 99 ? '99+' : count}</span>}
    </NavLink>
  );
}

export default function Shell({ title, children }: { title: string; children: ReactNode }) {
  const { isAdmin, roles, logout } = useAuth();
  const loc = useLocation();
  const nav = isAdmin ? ADMIN_NAV : CUSTOMER_NAV;
  const home = isAdmin ? '/admin' : '/products';

  return (
    <div className="shell">
      <aside className="rail">
        <NavLink to={home} className="brand" style={{ textDecoration: 'none' }}>
          <span className="brand-mark">DPP</span>
          <span className="brand-word">Pricing</span>
        </NavLink>

        <div className="nav-section">{isAdmin ? 'Quản trị' : 'Của tôi'}</div>
        {nav.map((n) => (
          <NavLink
            key={n.to}
            to={n.to}
            end={n.to === '/admin'}
            className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
          >
            <span className="nav-ico">{n.ico}</span>
            {n.label}
          </NavLink>
        ))}

        <div className="rail-foot">
          <div className="rail-user">{isAdmin ? 'Quản trị viên' : 'Tài khoản khách hàng'}</div>
          <button className="nav-link" onClick={logout} style={{ width: '100%', background: 'none', border: '1px solid rgba(255,255,255,.12)', cursor: 'pointer' }}>
            <span className="nav-ico">⏻</span> Đăng xuất
          </button>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <div className="topbar-title">{title}</div>
          <div className="row">
            {!isAdmin && <Bell />}
            <span className="tag">{roles[0] || '—'}</span>
          </div>
        </header>
        <main className="content" key={loc.pathname}>
          <ErrorBoundary resetKey={loc.pathname}>{children}</ErrorBoundary>
        </main>
      </div>
    </div>
  );
}
