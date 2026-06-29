import { useNavigate } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { useApi, timeAgo } from '../lib/format';
import { viNotifType } from '../lib/labels';
import { Loading, ErrorBanner, EmptyState, Spinner, useToast } from '../lib/ui';

interface Notification {
  notification_id: string; policy_id: string | null; type: string;
  message: string; created_at: string; read_at: string | null; read: boolean;
}

/* type -> icon + client route */
const TYPE_META: Record<string, { ico: string; route: (n: Notification) => string }> = {
  OrderApproved: { ico: '✓', route: () => '/orders' },
  OrderRejected: { ico: '✕', route: () => '/orders' },
  OrderSubmitted: { ico: '▤', route: () => '/orders' },
  PolicyIssued: { ico: '⛨', route: (n) => (n.policy_id ? `/policies/${n.policy_id}` : '/policies') },
  PolicyRenewed: { ico: '↻', route: (n) => (n.policy_id ? `/policies/${n.policy_id}` : '/policies') },
  PolicyCancelled: { ico: '⊘', route: (n) => (n.policy_id ? `/policies/${n.policy_id}` : '/policies') },
  EndorsementApplied: { ico: '✎', route: (n) => (n.policy_id ? `/policies/${n.policy_id}` : '/policies') },
  EndorsementRejected: { ico: '✎', route: (n) => (n.policy_id ? `/policies/${n.policy_id}` : '/policies') },
  EndorsementPendingPayment: { ico: '₫', route: (n) => (n.policy_id ? `/policies/${n.policy_id}` : '/policies') },
  EndorsementOverdue: { ico: '⏱', route: (n) => (n.policy_id ? `/policies/${n.policy_id}` : '/policies') },
  EndorsementCreditIssued: { ico: '◈', route: (n) => (n.policy_id ? `/policies/${n.policy_id}` : '/policies') },
  ClaimStatusChanged: { ico: '✚', route: () => '/claims' },
  ClaimSubmitted: { ico: '✚', route: () => '/claims' },
  RefundRequested: { ico: '↺', route: (n) => (n.policy_id ? `/policies/${n.policy_id}` : '/policies') },
  RefundCompleted: { ico: '↺', route: (n) => (n.policy_id ? `/policies/${n.policy_id}` : '/policies') },
  InvoiceVoided: { ico: '₫', route: () => '/orders' },
};
const fallback = { ico: '•', route: () => '/notifications' };

export default function Notifications() {
  const nav = useNavigate();
  const toast = useToast();
  const { data, error, loading, reload } = useApi<Notification[]>('/notifications');
  const unread = (data || []).filter((n) => !n.read).length;

  const markAll = async () => {
    await apiFetch('/notifications/read-all', { method: 'POST', body: {} });
    toast.push('Đã đánh dấu tất cả là đã đọc.');
    reload();
  };

  const open = async (n: Notification) => {
    if (!n.read) {
      try { await apiFetch(`/notifications/${n.notification_id}/read`, { method: 'PATCH' }); } catch { /* ignore */ }
    }
    const meta = TYPE_META[n.type] || fallback;
    nav(meta.route(n));
  };

  return (
    <div className="stack" style={{ maxWidth: 720 }}>
      <div className="row-between">
        <div>
          <p className="eyebrow">Hộp thư</p>
          <h2>Thông báo {unread > 0 && <span className="tag" style={{ verticalAlign: 'middle' }}>{unread} chưa đọc</span>}</h2>
        </div>
        {unread > 0 && <button className="btn btn-ghost btn-sm" onClick={markAll}>Đánh dấu tất cả đã đọc</button>}
      </div>

      {loading && <Loading />}
      <ErrorBanner error={error} />
      {data && data.length === 0 && <EmptyState mark="♪" title="Chưa có thông báo" hint="Cập nhật về đơn hàng, hợp đồng và bồi thường sẽ hiện ở đây." />}

      {data && data.length > 0 && (
        <div className="stack" style={{ gap: 'var(--s2)' }}>
          {data.map((n) => {
            const meta = TYPE_META[n.type] || fallback;
            return (
              <button
                key={n.notification_id}
                className="card"
                onClick={() => open(n)}
                style={{
                  textAlign: 'left', cursor: 'pointer', padding: 'var(--s4)',
                  borderLeft: n.read ? '1px solid var(--line)' : '3px solid var(--jade)',
                  background: n.read ? 'var(--raised)' : 'var(--jade-soft)',
                }}
              >
                <div className="row" style={{ alignItems: 'flex-start', gap: 'var(--s3)' }}>
                  <span style={{ fontSize: '1.1rem', width: 24, textAlign: 'center', color: 'var(--jade-ink)' }}>{meta.ico}</span>
                  <div className="grow">
                    <div className="row-between">
                      <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>{viNotifType(n.type)}</span>
                      <span className="faint mono" style={{ fontSize: '0.72rem' }}>{timeAgo(n.created_at)}</span>
                    </div>
                    <div className="muted" style={{ fontSize: '0.88rem', marginTop: 2 }}>{n.message}</div>
                  </div>
                </div>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
