import { Fragment, useState } from 'react';
import { ApiError } from '../../api/client';
import { humanize, useApi, useMutation, vndLabel, dateTime } from '../../lib/format';
import { viFeature, viProduct, viStatus, viEnum } from '../../lib/labels';
import { usePaged, Pager } from '../../lib/paged';
import { LINES, LINE_LABEL, Line } from '../../lib/domain';
import { Loading, ErrorBanner, EmptyState, StatusPill, Spinner, useToast } from '../../lib/ui';
import { TextAreaField } from '../../lib/fields';

interface OrderResponse {
  order_id: string; quote_id?: string; customer_id: string; product_id: string; final_premium_vnd: number;
  line?: string | null; trip_duration_days?: number | null; coverage_amount_vnd?: number | null;
  deductible_vnd?: number | null; risk_profile?: Record<string, unknown>;
  status: string; review_decision?: string | null; review_reason?: string | null;
  reviewed_by?: string | null; reviewed_at?: string | null; created_at: string; invoice_id: string | null;
}

type OrderWire = OrderResponse & Record<string, any>;

const STATUSES = ['PENDING_REVIEW', 'PENDING_PAYMENT', 'COMPLETED', 'REJECTED', 'CANCELLED'];

export default function AdminOrders() {
  const toast = useToast();
  const [mode, setMode] = useState<'queue' | 'all'>('queue');
  const [status, setStatus] = useState('');
  const [line, setLine] = useState('');
  const [viewing, setViewing] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<string | null>(null);
  const { run, busy } = useMutation();

  const base = mode === 'queue' ? '/admin/orders/review-queue' : '/admin/orders';
  const filters = mode === 'queue' ? { line } : { status, line };
  const { data, error, loading, page, setPage, reload } = usePaged<OrderResponse>(base, filters);

  const approve = async (id: string) => {
    try {
      await run(`/admin/orders/${id}/approve`, { method: 'POST', body: {} });
      toast.push('Order approved.');
      setViewing(null);
      reload();
    } catch (e) {
      handle(e as ApiError, toast);
    }
  };

  const reject = async (id: string, reason: string) => {
    try {
      await run(`/admin/orders/${id}/reject`, { method: 'POST', body: { reason } });
      toast.push('Order rejected.');
      setRejecting(null);
      setViewing(null);
      reload();
    } catch (e) {
      handle(e as ApiError, toast);
    }
  };

  const toggleDetails = (id: string) => {
    setViewing(viewing === id ? null : id);
    setRejecting(null);
  };

  return (
    <div className="stack">
      <div className="tabs">
        <button className={'tab' + (mode === 'queue' ? ' active' : '')} onClick={() => { setMode('queue'); setPage(0); }}>Review Queue</button>
        <button className={'tab' + (mode === 'all' ? ' active' : '')} onClick={() => { setMode('all'); setPage(0); }}>All Orders</button>
      </div>

      <div className="toolbar">
        {mode === 'all' && (
          <label className="field" style={{ margin: 0 }}>
            <span className="label">Status</span>
            <select className="select" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
              <option value="">All</option>
              {STATUSES.map((s) => <option key={s} value={s}>{viStatus(s)}</option>)}
            </select>
          </label>
        )}
        <label className="field" style={{ margin: 0 }}>
          <span className="label">Line</span>
          <select className="select" value={line} onChange={(e) => { setLine(e.target.value); setPage(0); }}>
            <option value="">All</option>
            {LINES.map((l) => <option key={l} value={l}>{LINE_LABEL[l as Line]}</option>)}
          </select>
        </label>
      </div>

      {loading && <Loading />}
      <ErrorBanner error={error} />
      {data && data.content.length === 0 && <EmptyState title="No orders" />}

      {data && data.content.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Order</th><th>Product</th><th>Premium</th><th>Status</th><th>Submitted</th><th></th></tr></thead>
            <tbody>
              {data.content.map((o) => (
                <Fragment key={o.order_id}>
                  <tr
                    role="button"
                    tabIndex={0}
                    style={{ cursor: 'pointer' }}
                    onClick={() => toggleDetails(o.order_id)}
                    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') toggleDetails(o.order_id); }}
                  >
                    <td className="mono" style={{ fontSize: '0.78rem' }}>{o.order_id.slice(0, 8)}</td>
                    <td className="mono">{viProduct(o.product_id)}</td>
                    <td className="num">{vndLabel(o.final_premium_vnd)}</td>
                    <td><StatusPill status={o.status} /></td>
                    <td className="muted">{dateTime(o.created_at)}</td>
                    <td className="num">
                      <div className="row" style={{ justifyContent: 'flex-end' }}>
                        {o.status === 'PENDING_REVIEW' && (
                          <>
                            <button className="btn btn-primary btn-sm" disabled={busy} onClick={(e) => { e.stopPropagation(); approve(o.order_id); }}>Approve</button>
                            <button className="btn btn-danger btn-sm" disabled={busy} onClick={(e) => { e.stopPropagation(); setRejecting(rejecting === o.order_id ? null : o.order_id); setViewing(null); }}>Reject</button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                  {viewing === o.order_id && (
                    <tr key={o.order_id + '-d'}>
                      <td colSpan={6} style={{ background: 'var(--surface-2)' }}>
                        <OrderDetailPanel orderId={o.order_id} busy={busy} onApprove={approve} onReject={(reason) => reject(o.order_id, reason)} onClose={() => setViewing(null)} />
                      </td>
                    </tr>
                  )}
                  {rejecting === o.order_id && (
                    <tr key={o.order_id + '-r'}>
                      <td colSpan={6} style={{ background: 'var(--surface-2)' }}>
                        <RejectInline busy={busy} onCancel={() => setRejecting(null)} onReject={(r) => reject(o.order_id, r)} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && <Pager page={page} totalPages={data.total_pages} total={data.total_elements} onPage={setPage} />}
    </div>
  );
}

function OrderDetailPanel({ orderId, busy, onApprove, onReject, onClose }: {
  orderId: string; busy: boolean; onApprove: (id: string) => void; onReject: (reason: string) => void; onClose: () => void;
}) {
  const [rejecting, setRejecting] = useState(false);
  const { data: rawOrder, error, loading } = useApi<OrderWire>(`/admin/orders/${orderId}`, [orderId]);
  const order = rawOrder ? normalizeOrder(rawOrder) : null;
  const riskEntries = order ? riskProfileEntries(order.risk_profile) : [];

  return (
    <div className="stack" style={{ padding: 'var(--s3) 0' }}>
      {loading && <Loading />}
      <ErrorBanner error={error} />
      {order && (
        <div className="card stack" style={{ background: 'var(--raised)' }}>
          <div className="row-between">
            <div>
              <span className="mono faint" style={{ fontSize: '0.78rem' }}>{order.order_id}</span>
              <h3 style={{ marginTop: 4 }}>{viProduct(order.product_id)}</h3>
            </div>
            <StatusPill status={order.status} />
          </div>

          <div className="panel">
            <div className="kv" style={{ borderTop: 'none' }}><span className="kv-k">Customer</span><span className="kv-v">{order.customer_id}</span></div>
            <div className="kv"><span className="kv-k">Quote</span><span className="kv-v">{order.quote_id || '-'}</span></div>
            <div className="kv"><span className="kv-k">Product Line</span><span className="kv-v">{lineLabel(order.line)}</span></div>
            <div className="kv"><span className="kv-k">Premium</span><span className="kv-v">{vndLabel(order.final_premium_vnd)}</span></div>
            {order.coverage_amount_vnd != null && <div className="kv"><span className="kv-k">Coverage</span><span className="kv-v">{vndLabel(order.coverage_amount_vnd)}</span></div>}
            {order.deductible_vnd != null && <div className="kv"><span className="kv-k">Deductible</span><span className="kv-v">{vndLabel(order.deductible_vnd)}</span></div>}
            {order.trip_duration_days != null && <div className="kv"><span className="kv-k">Trip Duration</span><span className="kv-v">{order.trip_duration_days}</span></div>}
            <div className="kv"><span className="kv-k">Submitted</span><span className="kv-v">{dateTime(order.created_at)}</span></div>
            {order.reviewed_at && <div className="kv"><span className="kv-k">Reviewed</span><span className="kv-v">{dateTime(order.reviewed_at)}</span></div>}
            {order.review_reason && <div className="kv"><span className="kv-k">Reason</span><span className="kv-v">{order.review_reason}</span></div>}
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Risk Profile</h4>
            {riskEntries.length === 0 && <p className="muted">No risk profile was stored for this order.</p>}
            {riskEntries.map(([key, value], idx) => (
              <div className="kv" key={key} style={idx === 0 ? { borderTop: 'none' } : undefined}>
                <span className="kv-k">{viFeature(key)}</span>
                <span className="kv-v">{formatRiskValue(value)}</span>
              </div>
            ))}
          </div>

          {order.status === 'PENDING_REVIEW' ? (
            <div className="row">
              <button className="btn btn-primary" disabled={busy} onClick={() => onApprove(order.order_id)}>{busy ? <Spinner /> : 'Approve Order'}</button>
              <button className="btn btn-danger" disabled={busy} onClick={() => setRejecting(!rejecting)}>Reject</button>
              <button className="btn btn-ghost" onClick={onClose}>Close</button>
            </div>
          ) : (
            <button className="btn btn-ghost" onClick={onClose}>Close</button>
          )}
          {rejecting && <RejectInline busy={busy} onCancel={() => setRejecting(false)} onReject={onReject} />}
        </div>
      )}
    </div>
  );
}

function lineLabel(line: string | null | undefined): string {
  return line ? humanize(line) : '-';
}

function normalizeOrder(order: OrderWire): OrderResponse {
  return {
    order_id: order.order_id ?? order.orderId,
    quote_id: order.quote_id ?? order.quoteId,
    customer_id: order.customer_id ?? order.customerId,
    product_id: order.product_id ?? order.productId,
    final_premium_vnd: order.final_premium_vnd ?? order.finalPremiumVnd,
    line: order.line,
    trip_duration_days: order.trip_duration_days ?? order.tripDurationDays,
    coverage_amount_vnd: order.coverage_amount_vnd ?? order.coverageAmountVnd,
    deductible_vnd: order.deductible_vnd ?? order.deductibleVnd,
    risk_profile: order.risk_profile ?? order.riskProfile ?? {},
    status: order.status,
    review_decision: order.review_decision ?? order.reviewDecision,
    review_reason: order.review_reason ?? order.reviewReason,
    reviewed_by: order.reviewed_by ?? order.reviewedBy,
    reviewed_at: order.reviewed_at ?? order.reviewedAt,
    created_at: order.created_at ?? order.createdAt,
    invoice_id: order.invoice_id ?? order.invoiceId,
  };
}

function riskProfileEntries(profile?: Record<string, unknown>): [string, unknown][] {
  const flat: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(profile || {})) {
    if (key === 'line_attributes' && value && typeof value === 'object' && !Array.isArray(value)) {
      Object.assign(flat, value as Record<string, unknown>);
    } else {
      flat[key] = value;
    }
  }
  return Object.entries(flat)
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .sort(([a], [b]) => a.localeCompare(b));
}

function formatRiskValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '-';
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : value.toFixed(4);
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (typeof value === 'string') return viEnum(value);
  return JSON.stringify(value);
}

function RejectInline({ busy, onReject, onCancel }: { busy: boolean; onReject: (r: string) => void; onCancel: () => void }) {
  const [reason, setReason] = useState('');
  return (
    <div className="stack" style={{ padding: 'var(--s3) 0' }}>
      <TextAreaField label="Rejection Reason" value={reason} onChange={setReason} placeholder="Provide a clear reason (required)" />
      <div className="row">
        <button className="btn btn-danger btn-sm" disabled={busy || !reason.trim()} onClick={() => onReject(reason)}>{busy ? <Spinner /> : 'Confirm Reject'}</button>
        <button className="btn btn-ghost btn-sm" onClick={onCancel}>Cancel</button>
      </div>
    </div>
  );
}

function handle(err: ApiError, toast: ReturnType<typeof useToast>) {
  if (err.code === 'ORDER_NOT_APPROVED') toast.push('Order is no longer pending review.', 'warn');
  else toast.push(err.message, 'err');
}
