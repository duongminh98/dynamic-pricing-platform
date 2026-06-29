import { Fragment, useState } from 'react';
import { ApiError } from '../../api/client';
import { useMutation, vndLabel, dateTime } from '../../lib/format';
import { viStatus, viEnum } from '../../lib/labels';
import { usePaged, Pager } from '../../lib/paged';
import { Loading, ErrorBanner, EmptyState, StatusPill, Spinner, useToast } from '../../lib/ui';
import { TextField, TextAreaField, NumberField, SelectField } from '../../lib/fields';

interface ClaimResponse {
  claim_id: string; policy_id: string; customer_id: string; loss_type: string;
  claim_status: string; occurrence_date: string; estimated_cost: number;
  incurred_amount_vnd?: number; paid_amount_vnd?: number; admin_note?: string | null;
}

const STATUSES = ['pending', 'approved', 'rejected'];
type Action = 'approve' | 'reject' | 'sanction';

export default function AdminClaims() {
  const [status, setStatus] = useState('pending');
  const [acting, setActing] = useState<{ id: string; action: Action } | null>(null);
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
                  <tr>
                    <td className="mono" style={{ fontSize: '0.78rem' }}>{c.claim_id.slice(0, 8)}</td>
                    <td>{viEnum(c.loss_type)}</td>
                    <td className="muted">{dateTime(c.occurrence_date)}</td>
                    <td className="num">{vndLabel(c.estimated_cost)}</td>
                    <td><StatusPill status={c.claim_status} /></td>
                    <td className="num">
                      {c.claim_status === 'pending' && (
                        <div className="row" style={{ justifyContent: 'flex-end' }}>
                          <button className="btn btn-primary btn-sm" onClick={() => setActing({ id: c.claim_id, action: 'approve' })}>Duyệt</button>
                          <button className="btn btn-danger btn-sm" onClick={() => setActing({ id: c.claim_id, action: 'reject' })}>Từ chối</button>
                          <button className="btn btn-ghost btn-sm" onClick={() => setActing({ id: c.claim_id, action: 'sanction' })}>Chế tài</button>
                        </div>
                      )}
                    </td>
                  </tr>
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
