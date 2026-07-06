import { Fragment, useState } from 'react';
import { ApiError } from '../../api/client';
import { useApi, useMutation, vndLabel, dateTime } from '../../lib/format';
import { viStatus, viEnum } from '../../lib/labels';
import { usePaged, Pager } from '../../lib/paged';
import { Loading, ErrorBanner, EmptyState, StatusPill, Spinner, useToast } from '../../lib/ui';
import { TextField, TextAreaField, NumberField, SelectField } from '../../lib/fields';

interface ClaimResponse {
  claim_id: string; policy_id: string; customer_id: string; loss_type: string;
  claim_status: string; occurrence_date: string; estimated_cost: number;
  incurred_amount_vnd?: number; paid_amount_vnd?: number; admin_note?: string | null;
}

interface ClaimDetailResponse {
  claim_id: string; policy_id: string; customer_id: string; exposure_segment_seq: number;
  loss_type: string; claim_status: string; occurrence_date: string; report_date: string;
  estimated_cost: number; incurred_amount: number; paid_amount: number;
  description: string | null; admin_note: string | null; payment_reference: string | null;
  paid_at: string | null; misrepresentation_sanction: string | null; attachments: string[];
  created_at: string;
}

const STATUSES = ['pending', 'approved', 'rejected'];
type Action = 'approve' | 'reject' | 'sanction';

export default function AdminClaims() {
  const [status, setStatus] = useState('pending');
  const [acting, setActing] = useState<{ id: string; action: Action } | null>(null);
  const [viewing, setViewing] = useState<string | null>(null);
  const { data, error, loading, page, setPage, reload } = usePaged<ClaimResponse>('/admin/claims', { status });

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Bồi thường</p>
        <h2>Xử lý bồi thường</h2>
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
      {data && data.content.length === 0 && <EmptyState title="Không có yêu cầu bồi thường" />}

      {data && data.content.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Mã</th><th>Loại</th><th>Xảy ra</th><th>Ước tính</th><th>Trạng thái</th><th></th></tr></thead>
            <tbody>
              {data.content.map((c) => (
                <Fragment key={c.claim_id}>
                  <tr
                    role="button"
                    tabIndex={0}
                    style={{ cursor: 'pointer' }}
                    onClick={() => { setViewing(viewing === c.claim_id ? null : c.claim_id); setActing(null); }}
                    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { setViewing(viewing === c.claim_id ? null : c.claim_id); setActing(null); } }}
                  >
                    <td className="mono" style={{ fontSize: '0.78rem' }}>{c.claim_id.slice(0, 8)}</td>
                    <td>{viEnum(c.loss_type)}</td>
                    <td className="muted">{dateTime(c.occurrence_date)}</td>
                    <td className="num">{vndLabel(c.estimated_cost)}</td>
                    <td><StatusPill status={c.claim_status} /></td>
                    <td className="num">
                      {c.claim_status === 'pending' && (
                        <div className="row" style={{ justifyContent: 'flex-end' }}>
                          <button className="btn btn-primary btn-sm" onClick={(e) => { e.stopPropagation(); setActing({ id: c.claim_id, action: 'approve' }); setViewing(null); }}>Duyệt</button>
                          <button className="btn btn-danger btn-sm" onClick={(e) => { e.stopPropagation(); setActing({ id: c.claim_id, action: 'reject' }); setViewing(null); }}>Từ chối</button>
                          <button className="btn btn-ghost btn-sm" onClick={(e) => { e.stopPropagation(); setActing({ id: c.claim_id, action: 'sanction' }); setViewing(null); }}>Chế tài</button>
                        </div>
                      )}
                    </td>
                  </tr>
                  {viewing === c.claim_id && (
                    <tr key={c.claim_id + '-d'}>
                      <td colSpan={6} style={{ background: 'var(--surface-2)' }}>
                        <ClaimDetailPanel claimId={c.claim_id} onClose={() => setViewing(null)} />
                      </td>
                    </tr>
                  )}
                  {acting?.id === c.claim_id && (
                    <tr key={c.claim_id + '-a'}>
                      <td colSpan={6} style={{ background: 'var(--surface-2)' }}>
                        <ClaimAction id={c.claim_id} action={acting.action} onDone={() => { setActing(null); reload(); }} onCancel={() => setActing(null)} />
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

function ClaimAction({ id, action, onDone, onCancel }: { id: string; action: Action; onDone: () => void; onCancel: () => void }) {
  const toast = useToast();
  const { run, busy, error } = useMutation();
  // approve
  const [incurred, setIncurred] = useState<number | ''>('');
  const [paid, setPaid] = useState<number | ''>('');
  const [payRef, setPayRef] = useState('');
  const [note, setNote] = useState('');
  // reject
  const [reason, setReason] = useState('');
  // sanction
  const [sanction, setSanction] = useState('proportional');
  const [paidPremium, setPaidPremium] = useState<number | ''>('');
  const [shouldPremium, setShouldPremium] = useState<number | ''>('');

  const submit = async () => {
    try {
      if (action === 'approve') {
        await run(`/claims/${id}/approve`, { method: 'POST', body: { incurred_amount: incurred || 0, paid_amount: paid || 0, payment_reference: payRef, admin_note: note } });
        toast.push('Đã duyệt bồi thường.');
      } else if (action === 'reject') {
        await run(`/claims/${id}/reject`, { method: 'POST', body: { reason } });
        toast.push('Đã từ chối bồi thường.');
      } else {
        await run(`/claims/${id}/sanction`, { method: 'POST', body: { sanction, reasons: reason ? [reason] : [], paid_premium: paidPremium || 0, should_premium: shouldPremium || 0 } });
        toast.push('Đã áp dụng chế tài.');
      }
      onDone();
    } catch (e) {
      const err = e as ApiError;
      if (err.code === 'PAID_AMOUNT_EXCEEDS_REMAINING_COVERAGE') toast.push('Số chi trả vượt hạn mức còn lại của hợp đồng.', 'warn');
      else toast.push(err.message, 'err');
    }
  };

  return (
    <div className="stack" style={{ padding: 'var(--s3) 0' }}>
      <ErrorBanner error={error} />
      {action === 'approve' && (
        <>
          <div className="form-grid">
            <NumberField label="Tổn thất ghi nhận (₫)" value={incurred} onChange={setIncurred} min={0} required />
            <NumberField label="Số chi trả (₫)" value={paid} onChange={setPaid} min={0} required hint="≤ STBH − miễn thường" />
          </div>
          <TextField label="Mã chứng từ thanh toán" value={payRef} onChange={setPayRef} placeholder="BANK-TXN-…" required />
          <TextField label="Ghi chú" value={note} onChange={setNote} />
        </>
      )}
      {action === 'reject' && <TextAreaField label="Lý do từ chối" value={reason} onChange={setReason} placeholder="Bắt buộc" />}
      {action === 'sanction' && (
        <>
          <SelectField label="Hình thức chế tài" value={sanction} onChange={setSanction} options={['reject', 'proportional', 'cancel']} labelFn={viEnum} />
          {sanction === 'proportional' && (
            <div className="form-grid">
              <NumberField label="Phí đã đóng (₫)" value={paidPremium} onChange={setPaidPremium} min={0} />
              <NumberField label="Phí lẽ ra phải đóng (₫)" value={shouldPremium} onChange={setShouldPremium} min={0} />
            </div>
          )}
          <TextAreaField label="Lý do" value={reason} onChange={setReason} placeholder="Mô tả vi phạm khai báo" />
        </>
      )}
      <div className="row">
        <button className="btn btn-primary btn-sm" disabled={busy} onClick={submit}>{busy ? <Spinner /> : 'Xác nhận'}</button>
        <button className="btn btn-ghost btn-sm" onClick={onCancel}>Hủy</button>
      </div>
    </div>
  );
}

function ClaimDetailPanel({ claimId, onClose }: { claimId: string; onClose: () => void }) {
  const { data: claim, error, loading } = useApi<ClaimDetailResponse>(`/admin/claims/${claimId}`, [claimId]);

  return (
    <div className="stack" style={{ padding: 'var(--s3) 0' }}>
      {loading && <Loading />}
      <ErrorBanner error={error} />
      {claim && (
        <div className="card stack" style={{ background: 'var(--raised)' }}>
          <div className="row-between">
            <div>
              <span className="mono faint" style={{ fontSize: '0.78rem' }}>{claim.claim_id}</span>
              <h3 style={{ marginTop: 4 }}>{viEnum(claim.loss_type)}</h3>
            </div>
            <StatusPill status={claim.claim_status} />
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Thông tin bồi thường</h4>
            <Kv label="Hợp đồng" value={claim.policy_id} first />
            <Kv label="Khách hàng" value={claim.customer_id} />
            <Kv label="Kỳ phơi nhiễm" value={String(claim.exposure_segment_seq)} />
            <Kv label="Ngày xảy ra" value={dateTime(claim.occurrence_date)} />
            <Kv label="Ngày khai báo" value={dateTime(claim.report_date)} />
            <Kv label="Chi phí ước tính" value={vndLabel(claim.estimated_cost)} />
            <Kv label="Mô tả" value={claim.description || '-'} />
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Thẩm định & chi trả</h4>
            <Kv label="Tổn thất ghi nhận" value={vndLabel(claim.incurred_amount)} first />
            <Kv label="Số chi trả" value={vndLabel(claim.paid_amount)} />
            <Kv label="Mã chứng từ" value={claim.payment_reference || '-'} />
            <Kv label="Ngày chi trả" value={claim.paid_at ? dateTime(claim.paid_at) : '-'} />
            <Kv label="Chế tài khai báo" value={claim.misrepresentation_sanction ? viEnum(claim.misrepresentation_sanction) : '-'} />
            <Kv label="Ghi chú thẩm định" value={claim.admin_note || '-'} />
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Chứng từ đính kèm</h4>
            {claim.attachments.length === 0 && <p className="muted">Không có chứng từ.</p>}
            {claim.attachments.map((a, idx) => (
              <div className="kv" key={idx} style={idx === 0 ? { borderTop: 'none' } : undefined}>
                <span className="kv-k">#{idx + 1}</span>
                <span className="kv-v mono" style={{ fontSize: '0.78rem' }}>{a}</span>
              </div>
            ))}
          </div>

          <button className="btn btn-ghost" onClick={onClose}>Đóng</button>
        </div>
      )}
    </div>
  );
}

function Kv({ label, value, first = false }: { label: string; value: string; first?: boolean }) {
  return <div className="kv" style={first ? { borderTop: 'none' } : undefined}><span className="kv-k">{label}</span><span className="kv-v">{value}</span></div>;
}
