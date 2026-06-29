import { Link, useNavigate } from 'react-router-dom';
import { useApi, vndLabel, dateTime } from '../../lib/format';
import { LINE_LABEL, LINE_ICON, LINES, Line } from '../../lib/domain';
import { Loading, EmptyState, StatusPill } from '../../lib/ui';

interface ReviewItem {
  order_id: string; customer_id: string; product_id: string;
  final_premium_vnd: number; status: string; line: string; created_at: string;
}
interface Page<T> { content: T[]; total_elements: number; }
interface Drift { line: string; needs_recalibration: boolean; }

export default function AdminOverview() {
  const nav = useNavigate();
  const { data: queue, loading } = useApi<Page<ReviewItem>>('/admin/orders/review-queue?page=0&size=5');
  const { data: claimsQ } = useApi<Page<any>>('/admin/claims?status=pending&page=0&size=1');
  const { data: endorseQ } = useApi<Page<any>>('/admin/endorsements?status=PENDING_REVIEW&page=0&size=1');
  const { data: refundsQ } = useApi<Page<any>>('/admin/refunds?status=pending&page=0&size=1');

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Bảng điều khiển</p>
        <h2>Tổng quan vận hành</h2>
      </div>

      <div className="cards-grid">
        <StatCard label="Đơn chờ duyệt" value={queue?.total_elements} to="/admin/orders" ico="▤" />
        <StatCard label="Bồi thường chờ xử lý" value={claimsQ?.total_elements} to="/admin/claims" ico="✚" />
        <StatCard label="Sửa đổi chờ duyệt" value={endorseQ?.total_elements} to="/admin/endorsements" ico="✎" />
        <StatCard label="Hoàn tiền chờ xử lý" value={refundsQ?.total_elements} to="/admin/refunds" ico="↺" />
      </div>

      <div className="card stack">
        <div className="row-between">
          <h3 style={{ fontSize: 'var(--step-1)' }}>Hàng đợi duyệt đơn</h3>
          <Link to="/admin/orders" className="btn-link">Xem tất cả →</Link>
        </div>
        {loading && <Loading />}
        {queue && queue.content.length === 0 && <EmptyState title="Không có đơn nào chờ duyệt" />}
        {queue && queue.content.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Sản phẩm</th><th>Dòng</th><th>Phí</th><th>Trạng thái</th><th>Gửi lúc</th></tr></thead>
              <tbody>
                {queue.content.map((o) => (
                  <tr key={o.order_id} style={{ cursor: 'pointer' }} onClick={() => nav('/admin/orders')}>
                    <td className="mono">{o.product_id}</td>
                    <td>{LINE_ICON[o.line as Line]} {LINE_LABEL[o.line as Line] || o.line}</td>
                    <td className="num">{vndLabel(o.final_premium_vnd)}</td>
                    <td><StatusPill status={o.status} /></td>
                    <td className="muted">{dateTime(o.created_at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <DriftStrip />
    </div>
  );
}

function StatCard({ label, value, to, ico }: { label: string; value?: number; to: string; ico: string }) {
  return (
    <Link to={to} className="card prod-card" style={{ textDecoration: 'none' }}>
      <div className="row-between">
        <span className="stat-l">{label}</span>
        <span style={{ fontSize: '1.2rem', opacity: 0.5 }}>{ico}</span>
      </div>
      <span className="figure" style={{ fontSize: 'var(--step-3)' }}>{value ?? '—'}</span>
    </Link>
  );
}

function DriftStrip() {
  return (
    <div className="card stack">
      <h3 style={{ fontSize: 'var(--step-1)' }}>Model Drift Status</h3>
      <div className="cards-grid">
        {LINES.map((l) => <DriftCell key={l} line={l} />)}
      </div>
      <Link to="/admin/models" className="btn-link">Chi tiết quản trị mô hình →</Link>
    </div>
  );
}

function DriftCell({ line }: { line: Line }) {
  const { data } = useApi<Drift | Drift[]>(`/pricing/drift?line=${line}`, [line]);
  // The endpoint returns an array of all lines (ignoring ?line=); pick ours.
  const drift = Array.isArray(data) ? data.find((d) => d.line === line) ?? null : data;
  const needs = drift?.needs_recalibration;
  return (
    <div className="panel row-between">
      <span>{LINE_ICON[line]} {LINE_LABEL[line]}</span>
      {drift ? (
        <span className={'pill ' + (needs ? 'pill-bad' : 'pill-ok')}>{needs ? 'needs recalibration' : 'stable'}</span>
      ) : (
        <span className="pill pill-muted">—</span>
      )}
    </div>
  );
}
