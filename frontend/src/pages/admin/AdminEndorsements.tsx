import { Fragment, useState } from 'react';
import { ApiError } from '../../api/client';
import { useApi, useMutation, vndLabel, dateTime, dateOnly } from '../../lib/format';
import { viStatus, viFeature } from '../../lib/labels';
import { usePaged, Pager } from '../../lib/paged';
import { Loading, ErrorBanner, EmptyState, StatusPill, Spinner, useToast } from '../../lib/ui';
import { TextAreaField } from '../../lib/fields';

interface EndorsementReq {
  endorsement_request_id: string; policy_id: string; status: string;
  effective_date: string; difference_vnd: number; pro_rated_charge_vnd?: number;
  invoice_id: string | null;
}

interface EndorsementDetail {
  endorsement_request_id: string; policy_id: string; customer_id: string; status: string;
  effective_date: string; created_at: string; material_change: boolean;
  current_premium_vnd: number | null; quoted_premium_vnd: number | null; difference_vnd: number | null;
  review_reason: string | null; reviewed_by: string | null; reviewed_at: string | null;
  invoice_id: string | null; due_date: string | null; cancelled_at: string | null;
  pricing_failed_reason: string | null; change: Record<string, unknown> | null;
}

const STATUSES = ['PRICING_PENDING', 'PENDING_REVIEW', 'APPROVED_PENDING_PAYMENT', 'APPLIED', 'REJECTED', 'VOID', 'CANCELLED', 'PRICING_FAILED'];

export default function AdminEndorsements() {
  const toast = useToast();
  const [status, setStatus] = useState('PENDING_REVIEW');
  const [viewing, setViewing] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<string | null>(null);
  const { run, busy } = useMutation();
  const { data, error, loading, page, setPage, reload } = usePaged<EndorsementReq>('/admin/endorsements', { status });

  const approve = async (id: string) => {
    try { await run(`/admin/endorsements/${id}/approve`, { method: 'POST', body: {} }); toast.push('Đã duyệt.'); reload(); }
    catch (e) { toast.push((e as ApiError).message, 'err'); }
  };
  const reject = async (id: string, reason: string) => {
    try { await run(`/admin/endorsements/${id}/reject`, { method: 'POST', body: { reason } }); toast.push('Đã từ chối.'); setRejecting(null); reload(); }
    catch (e) { toast.push((e as ApiError).message, 'err'); }
  };
  const extend = async (id: string) => {
    try { await run(`/admin/endorsements/${id}/extend-due-date`, { method: 'POST', body: { extra_days: 7 } }); toast.push('Đã gia hạn 7 ngày.'); reload(); }
    catch (e) { toast.push((e as ApiError).message, 'err'); }
  };

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Sửa đổi hợp đồng</p>
        <h2>Yêu cầu sửa đổi</h2>
      </div>

      <div className="toolbar">
        <label className="field" style={{ margin: 0 }}>
          <span className="label">Trạng thái</span>
          <select className="select" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
            <option value="">Tất cả</option>
            {STATUSES.map((s) => <option key={s} value={s}>{viStatus(s)}</option>)}
          </select>
        </label>
      </div>

      {loading && <Loading />}
      <ErrorBanner error={error} />
      {data && data.content.length === 0 && <EmptyState title="Không có yêu cầu sửa đổi" />}

      {data && data.content.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Mã</th><th>Hợp đồng</th><th>Hiệu lực</th><th>Chênh lệch</th><th>Trạng thái</th><th></th></tr></thead>
            <tbody>
              {data.content.map((e) => (
                <Fragment key={e.endorsement_request_id}>
                  <tr
                    role="button"
                    tabIndex={0}
                    style={{ cursor: 'pointer' }}
                    onClick={() => setViewing(viewing === e.endorsement_request_id ? null : e.endorsement_request_id)}
                    onKeyDown={(ev) => { if (ev.key === 'Enter' || ev.key === ' ') setViewing(viewing === e.endorsement_request_id ? null : e.endorsement_request_id); }}
                  >
                    <td className="mono" style={{ fontSize: '0.78rem' }}>{e.endorsement_request_id.slice(0, 8)}</td>
                    <td className="mono" style={{ fontSize: '0.78rem' }}>{e.policy_id.slice(0, 8)}</td>
                    <td className="muted">{dateTime(e.effective_date)}</td>
                    <td className="num">{vndLabel(e.difference_vnd)}</td>
                    <td><StatusPill status={e.status} /></td>
                    <td className="num">
                      {e.status === 'PENDING_REVIEW' && (
                        <div className="row" style={{ justifyContent: 'flex-end' }}>
                          <button className="btn btn-primary btn-sm" disabled={busy} onClick={(ev) => { ev.stopPropagation(); approve(e.endorsement_request_id); }}>Duyệt</button>
                          <button className="btn btn-danger btn-sm" disabled={busy} onClick={(ev) => { ev.stopPropagation(); setViewing(null); setRejecting(rejecting === e.endorsement_request_id ? null : e.endorsement_request_id); }}>Từ chối</button>
                        </div>
                      )}
                      {e.status === 'APPROVED_PENDING_PAYMENT' && (
                        <button className="btn btn-ghost btn-sm" disabled={busy} onClick={(ev) => { ev.stopPropagation(); extend(e.endorsement_request_id); }}>+7 ngày</button>
                      )}
                      {e.status === 'VOID' && (
                        <button className="btn btn-ghost btn-sm" disabled={busy} onClick={(ev) => { ev.stopPropagation(); extend(e.endorsement_request_id); }}>Gia hạn lại</button>
                      )}
                    </td>
                  </tr>
                  {viewing === e.endorsement_request_id && (
                    <tr key={e.endorsement_request_id + '-d'}>
                      <td colSpan={6} style={{ background: 'var(--surface-2)' }}>
                        <EndorsementDetailPanel id={e.endorsement_request_id} onClose={() => setViewing(null)} />
                      </td>
                    </tr>
                  )}
                  {rejecting === e.endorsement_request_id && (
                    <tr key={e.endorsement_request_id + '-r'}>
                      <td colSpan={6} style={{ background: 'var(--surface-2)' }}>
                        <RejectForm busy={busy} onSubmit={(reason) => reject(e.endorsement_request_id, reason)} onCancel={() => setRejecting(null)} />
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

function EndorsementDetailPanel({ id, onClose }: { id: string; onClose: () => void }) {
  const { data: e, error, loading } = useApi<EndorsementDetail>(`/admin/endorsements/${id}`, [id]);

  return (
    <div className="stack" style={{ padding: 'var(--s3) 0' }}>
      {loading && <Loading />}
      <ErrorBanner error={error} />
      {e && (
        <div className="card stack" style={{ background: 'var(--raised)' }}>
          <div className="row-between">
            <div>
              <span className="mono faint" style={{ fontSize: '0.78rem' }}>{e.endorsement_request_id}</span>
              <h3 style={{ marginTop: 4 }}>Yêu cầu sửa đổi</h3>
            </div>
            <StatusPill status={e.status} />
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Thông tin</h4>
            <Kv label="Hợp đồng" value={e.policy_id} first />
            <Kv label="Khách hàng" value={e.customer_id} />
            <Kv label="Loại" value={e.material_change ? 'Thay đổi trọng yếu' : 'Thường'} />
            <Kv label="Hiệu lực" value={dateOnly(e.effective_date)} />
            <Kv label="Tạo lúc" value={dateTime(e.created_at)} />
            {e.due_date && <Kv label="Hạn thanh toán" value={dateTime(e.due_date)} />}
            {e.cancelled_at && <Kv label="Hủy lúc" value={dateTime(e.cancelled_at)} />}
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Phí</h4>
            <Kv label="Phí hiện tại" value={e.current_premium_vnd == null ? '-' : vndLabel(e.current_premium_vnd)} first />
            <Kv label="Phí báo giá" value={e.quoted_premium_vnd == null ? '-' : vndLabel(e.quoted_premium_vnd)} />
            <Kv label="Chênh lệch" value={e.difference_vnd == null ? '-' : vndLabel(e.difference_vnd)} />
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Nội dung thay đổi</h4>
            {(!e.change || Object.keys(e.change).length === 0) && <p className="muted">Không có.</p>}
            {e.change && Object.entries(e.change).map(([k, v], idx) => (
              <div className="kv" key={k} style={idx === 0 ? { borderTop: 'none' } : undefined}>
                <span className="kv-k">{viFeature(k)}</span>
                <span className="kv-v">{String(v)}</span>
              </div>
            ))}
          </div>

          {(e.reviewed_by || e.review_reason || e.pricing_failed_reason) && (
            <div className="panel">
              <h4 style={{ marginTop: 0 }}>Thẩm định</h4>
              {e.reviewed_by && <Kv label="Người duyệt" value={e.reviewed_by} first />}
              {e.reviewed_at && <Kv label="Duyệt lúc" value={dateTime(e.reviewed_at)} />}
              {e.review_reason && <Kv label="Lý do" value={e.review_reason} />}
              {e.pricing_failed_reason && <Kv label="Lỗi định phí" value={e.pricing_failed_reason} />}
            </div>
          )}

          <button className="btn btn-ghost" onClick={onClose}>Đóng</button>
        </div>
      )}
    </div>
  );
}

function RejectForm({ busy, onSubmit, onCancel }: { busy: boolean; onSubmit: (reason: string) => void; onCancel: () => void }) {
  const [reason, setReason] = useState('');
  return (
    <div className="stack" style={{ padding: 'var(--s3) 0' }}>
      <TextAreaField label="Lý do từ chối" value={reason} onChange={setReason} placeholder="Bắt buộc" required />
      <div className="row">
        <button className="btn btn-danger btn-sm" disabled={busy || !reason.trim()} onClick={() => onSubmit(reason.trim())}>{busy ? <Spinner /> : 'Xác nhận từ chối'}</button>
        <button className="btn btn-ghost btn-sm" onClick={onCancel}>Hủy</button>
      </div>
    </div>
  );
}

function Kv({ label, value, first = false }: { label: string; value: string; first?: boolean }) {
  return <div className="kv" style={first ? { borderTop: 'none' } : undefined}><span className="kv-k">{label}</span><span className="kv-v">{value}</span></div>;
}
