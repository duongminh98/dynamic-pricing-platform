import { Fragment, useState } from 'react';
import { ApiError } from '../../api/client';
import { useMutation, vndLabel, dateTime } from '../../lib/format';
import { viStatus } from '../../lib/labels';
import { usePaged, Pager } from '../../lib/paged';
import { Loading, ErrorBanner, EmptyState, StatusPill, Spinner, useToast } from '../../lib/ui';
import { TextField, TextAreaField } from '../../lib/fields';

interface RefundResponse {
  refund_id: string; policy_id: string; customer_id: string; credit_id: string;
  amount_vnd: number; status: string; payment_reference: string | null;
  note: string | null; requested_at: string;
}

const STATUSES = ['pending', 'completed', 'rejected'];

export default function AdminRefunds() {
  const [status, setStatus] = useState('pending');
  const [acting, setActing] = useState<{ id: string; action: 'complete' | 'reject' } | null>(null);
  const { data, error, loading, page, setPage, reload } = usePaged<RefundResponse>('/admin/refunds', { status });

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Tài chính</p>
        <h2>Hoàn tiền</h2>
        <p className="muted" style={{ marginTop: 8 }}>Hoàn tiền do hệ thống tạo khi hủy hợp đồng. Quản trị viên ghi nhận chuyển khoản hoặc từ chối (khôi phục tín dụng).</p>
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
      {data && data.content.length === 0 && (
        <EmptyState title="No refund information" hint="Refund requests appear after billing consumes cancellation or credit events." />
      )}

      {data && data.content.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Mã</th><th>Hợp đồng</th><th>Số tiền</th><th>Trạng thái</th><th>Tạo lúc</th><th></th></tr></thead>
            <tbody>
              {data.content.map((r) => (
                <Fragment key={r.refund_id}>
                  <tr>
                    <td className="mono" style={{ fontSize: '0.78rem' }}>{r.refund_id.slice(0, 8)}</td>
                    <td className="mono" style={{ fontSize: '0.78rem' }}>{r.policy_id.slice(0, 8)}</td>
                    <td className="num">{vndLabel(r.amount_vnd)}</td>
                    <td><StatusPill status={r.status} /></td>
                    <td className="muted">{dateTime(r.requested_at)}</td>
                    <td className="num">
                      {r.status === 'pending' && (
                        <div className="row" style={{ justifyContent: 'flex-end' }}>
                          <button className="btn btn-primary btn-sm" onClick={() => setActing({ id: r.refund_id, action: 'complete' })}>Hoàn tất</button>
                          <button className="btn btn-danger btn-sm" onClick={() => setActing({ id: r.refund_id, action: 'reject' })}>Từ chối</button>
                        </div>
                      )}
                    </td>
                  </tr>
                  {acting?.id === r.refund_id && (
                    <tr key={r.refund_id + '-a'}>
                      <td colSpan={6} style={{ background: 'var(--surface-2)' }}>
                        <RefundAction id={r.refund_id} action={acting.action} onDone={() => { setActing(null); reload(); }} onCancel={() => setActing(null)} />
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

function RefundAction({ id, action, onDone, onCancel }: { id: string; action: 'complete' | 'reject'; onDone: () => void; onCancel: () => void }) {
  const toast = useToast();
  const { run, busy, error } = useMutation();
  const [payRef, setPayRef] = useState('');
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');

  const submit = async () => {
    try {
      if (action === 'complete') {
        await run(`/admin/refunds/${id}/complete`, { method: 'POST', body: { payment_reference: payRef, note } });
        toast.push('Đã hoàn tất hoàn tiền.');
      } else {
        await run(`/admin/refunds/${id}/reject`, { method: 'POST', body: { reason } });
        toast.push('Đã từ chối; tín dụng được khôi phục.');
      }
      onDone();
    } catch (e) { toast.push((e as ApiError).message, 'err'); }
  };

  return (
    <div className="stack" style={{ padding: 'var(--s3) 0' }}>
      <ErrorBanner error={error} />
      {action === 'complete' ? (
        <>
          <TextField label="Mã chứng từ chuyển khoản" value={payRef} onChange={setPayRef} placeholder="BANK-TXN-…" required />
          <TextField label="Ghi chú" value={note} onChange={setNote} />
        </>
      ) : (
        <TextAreaField label="Lý do từ chối" value={reason} onChange={setReason} placeholder="Bắt buộc" />
      )}
      <div className="row">
        <button className="btn btn-primary btn-sm" disabled={busy || (action === 'complete' ? !payRef.trim() : !reason.trim())} onClick={submit}>{busy ? <Spinner /> : 'Xác nhận'}</button>
        <button className="btn btn-ghost btn-sm" onClick={onCancel}>Hủy</button>
      </div>
    </div>
  );
}
