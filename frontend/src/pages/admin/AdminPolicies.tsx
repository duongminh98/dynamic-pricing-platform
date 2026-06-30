import { Fragment, useState } from 'react';
import { ApiError } from '../../api/client';
import { humanize, useApi, useMutation, vndLabel, dateOnly, dateTime } from '../../lib/format';
import { viStatus } from '../../lib/labels';
import { usePaged, Pager } from '../../lib/paged';
import { LINES, LINE_LABEL, Line } from '../../lib/domain';
import { Loading, ErrorBanner, EmptyState, StatusPill, Spinner, useToast } from '../../lib/ui';

interface PolicyResponse {
  policy_id: string; customer_id: string; product_id: string; line: string | null;
  status: string; policy_effective_date: string; policy_expiration_date: string;
  renewal_number: number; final_premium_vnd: number;
}

interface ExposureSegmentResponse {
  segment_id: string; exposure_segment_seq: number; segment_start: string; segment_end: string;
  earned_exposure_years: number; coverage_amount_vnd: number; deductible_vnd: number;
}

interface EndorsementResponse {
  endorsement_request_id: string; status: string; effective_date: string; quoted_premium_vnd: number | null;
  review_reason: string | null; reviewed_by: string | null; reviewed_at: string | null; created_at: string;
  invoice_id: string | null; due_date: string | null; change?: Record<string, unknown>;
}

interface PolicyDocumentResponse {
  document_id: string; version: number; content: string; created_at: string;
}

interface PolicyDetailResponse extends PolicyResponse {
  order_id: string; renewal: boolean; years_since_first_policy: number; policy_count_prior: number;
  asset_key: string | null; cancel_date: string | null; created_at: string;
  exposure_segments: ExposureSegmentResponse[]; endorsements: EndorsementResponse[]; documents: PolicyDocumentResponse[];
}

const STATUSES = ['active', 'cancelled', 'expired'];

export default function AdminPolicies() {
  const toast = useToast();
  const [status, setStatus] = useState('');
  const [line, setLine] = useState('');
  const [viewing, setViewing] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState<string | null>(null);
  const { run, busy } = useMutation();
  const { data, error, loading, page, setPage, reload } = usePaged<PolicyResponse>('/admin/policies', { status, line });

  const cancel = async (id: string) => {
    try { await run(`/admin/policies/${id}/cancel`, { method: 'POST', body: {} }); toast.push('Đã hủy hợp đồng.'); setCancelling(null); reload(); }
    catch (e) { toast.push((e as ApiError).message, 'err'); }
  };

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Hợp đồng</p>
        <h2>Quản lý hợp đồng</h2>
      </div>

      <div className="toolbar">
        <label className="field" style={{ margin: 0 }}>
          <span className="label">Trạng thái</span>
          <select className="select" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
            <option value="">Tất cả</option>
            {STATUSES.map((s) => <option key={s} value={s}>{viStatus(s)}</option>)}
          </select>
        </label>
        <label className="field" style={{ margin: 0 }}>
          <span className="label">Dòng</span>
          <select className="select" value={line} onChange={(e) => { setLine(e.target.value); setPage(0); }}>
            <option value="">Tất cả</option>
            {LINES.map((l) => <option key={l} value={l}>{LINE_LABEL[l as Line]}</option>)}
          </select>
        </label>
      </div>

      {loading && <Loading />}
      <ErrorBanner error={error} />
      {data && data.content.length === 0 && <EmptyState title="Không có hợp đồng" />}

      {data && data.content.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Mã HĐ</th><th>Sản phẩm</th><th>Phí</th><th>Hiệu lực</th><th>Hết hạn</th><th>Trạng thái</th><th></th></tr></thead>
            <tbody>
              {data.content.map((p) => (
                <Fragment key={p.policy_id}>
                  <tr
                    role="button"
                    tabIndex={0}
                    style={{ cursor: 'pointer' }}
                    onClick={() => { setViewing(viewing === p.policy_id ? null : p.policy_id); setCancelling(null); }}
                    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { setViewing(viewing === p.policy_id ? null : p.policy_id); setCancelling(null); } }}
                  >
                    <td className="mono" style={{ fontSize: '0.78rem' }}>{p.policy_id.slice(0, 8)}</td>
                    <td className="mono">{p.product_id}</td>
                    <td className="num">{vndLabel(p.final_premium_vnd)}</td>
                    <td className="muted">{dateOnly(p.policy_effective_date)}</td>
                    <td className="muted">{dateOnly(p.policy_expiration_date)}</td>
                    <td><StatusPill status={p.status} /></td>
                    <td className="num">
                      {p.status === 'active' && (
                        <button className="btn btn-danger btn-sm" onClick={(e) => { e.stopPropagation(); setCancelling(cancelling === p.policy_id ? null : p.policy_id); }}>Hủy</button>
                      )}
                    </td>
                  </tr>
                  {viewing === p.policy_id && (
                    <tr key={p.policy_id + '-d'}>
                      <td colSpan={7} style={{ background: 'var(--surface-2)' }}>
                        <PolicyDetailPanel policyId={p.policy_id} onClose={() => setViewing(null)} />
                      </td>
                    </tr>
                  )}
                  {cancelling === p.policy_id && (
                    <tr key={p.policy_id + '-c'}>
                      <td colSpan={7} style={{ background: 'var(--surface-2)' }}>
                        <div className="row" style={{ padding: 'var(--s3) 0' }}>
                          <span className="muted">Hủy hợp đồng này (hiệu lực từ hôm nay)?</span>
                          <button className="btn btn-danger btn-sm" disabled={busy} onClick={() => cancel(p.policy_id)}>{busy ? <Spinner /> : 'Xác nhận hủy'}</button>
                          <button className="btn btn-ghost btn-sm" onClick={() => setCancelling(null)}>Bỏ qua</button>
                        </div>
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

function PolicyDetailPanel({ policyId, onClose }: { policyId: string; onClose: () => void }) {
  const { data: policy, error, loading } = useApi<PolicyDetailResponse>(`/admin/policies/${policyId}`, [policyId]);

  return (
    <div className="stack" style={{ padding: 'var(--s3) 0' }}>
      {loading && <Loading />}
      <ErrorBanner error={error} />
      {policy && (
        <div className="card stack" style={{ background: 'var(--raised)' }}>
          <div className="row-between">
            <div>
              <span className="mono faint" style={{ fontSize: '0.78rem' }}>{policy.policy_id}</span>
              <h3 style={{ marginTop: 4 }}>{policy.product_id}</h3>
            </div>
            <StatusPill status={policy.status} />
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Policy Summary</h4>
            <Kv label="Customer" value={policy.customer_id} first />
            <Kv label="Order" value={policy.order_id} />
            <Kv label="Line" value={policy.line ? humanize(policy.line) : '-'} />
            <Kv label="Premium" value={vndLabel(policy.final_premium_vnd)} />
            <Kv label="Effective" value={dateOnly(policy.policy_effective_date)} />
            <Kv label="Expiration" value={dateOnly(policy.policy_expiration_date)} />
            <Kv label="Asset Key" value={policy.asset_key || '-'} />
            <Kv label="Renewal" value={policy.renewal ? `Yes (#${policy.renewal_number})` : 'No'} />
            <Kv label="Prior Policies" value={String(policy.policy_count_prior)} />
            <Kv label="Years Since First Policy" value={String(policy.years_since_first_policy)} />
            <Kv label="Created" value={dateTime(policy.created_at)} />
            {policy.cancel_date && <Kv label="Cancelled" value={dateTime(policy.cancel_date)} />}
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Exposure Segments</h4>
            {policy.exposure_segments.length === 0 && <p className="muted">No exposure segments.</p>}
            {policy.exposure_segments.map((s, idx) => (
              <div key={s.segment_id} className="panel" style={{ marginTop: idx === 0 ? 0 : 'var(--s3)' }}>
                <Kv label="Sequence" value={String(s.exposure_segment_seq)} first />
                <Kv label="Period" value={`${dateOnly(s.segment_start)} - ${dateOnly(s.segment_end)}`} />
                <Kv label="Coverage" value={vndLabel(s.coverage_amount_vnd)} />
                <Kv label="Deductible" value={vndLabel(s.deductible_vnd)} />
                <Kv label="Earned Exposure" value={s.earned_exposure_years.toFixed(4)} />
              </div>
            ))}
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Endorsements</h4>
            {policy.endorsements.length === 0 && <p className="muted">No endorsements.</p>}
            {policy.endorsements.map((e, idx) => (
              <div key={e.endorsement_request_id} className="kv" style={idx === 0 ? { borderTop: 'none' } : undefined}>
                <span className="kv-k"><StatusPill status={e.status} /></span>
                <span className="kv-v">{dateOnly(e.effective_date)} · {e.quoted_premium_vnd == null ? '-' : vndLabel(e.quoted_premium_vnd)}</span>
              </div>
            ))}
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Documents</h4>
            {policy.documents.length === 0 && <p className="muted">No policy documents.</p>}
            {policy.documents.map((d, idx) => (
              <div className="kv" key={d.document_id} style={idx === 0 ? { borderTop: 'none' } : undefined}>
                <span className="kv-k">Version {d.version}</span>
                <span className="kv-v">{dateTime(d.created_at)}</span>
              </div>
            ))}
          </div>

          <button className="btn btn-ghost" onClick={onClose}>Close</button>
        </div>
      )}
    </div>
  );
}

function Kv({ label, value, first = false }: { label: string; value: string; first?: boolean }) {
  return <div className="kv" style={first ? { borderTop: 'none' } : undefined}><span className="kv-k">{label}</span><span className="kv-v">{value}</span></div>;
}
