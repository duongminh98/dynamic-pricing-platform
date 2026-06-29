import { useState } from 'react';
import { ApiError } from '../../api/client';
import { useMutation, vndLabel, dateTime } from '../../lib/format';
import { viStatus } from '../../lib/labels';
import { usePaged, Pager } from '../../lib/paged';
import { Loading, ErrorBanner, EmptyState, StatusPill, Spinner, useToast } from '../../lib/ui';

interface EndorsementReq {
  endorsement_request_id: string; policy_id: string; status: string;
  effective_date: string; difference_vnd: number; pro_rated_charge_vnd?: number;
  invoice_id: string | null;
}

const STATUSES = ['PENDING_REVIEW', 'APPROVED', 'APPLIED', 'REJECTED', 'CANCELLED'];

export default function AdminEndorsements() {
  const toast = useToast();
  const [status, setStatus] = useState('PENDING_REVIEW');
  const { run, busy } = useMutation();
  const { data, error, loading, page, setPage, reload } = usePaged<EndorsementReq>('/admin/endorsements', { status });

  const act = async (id: string, action: 'approve' | 'reject', body: any = {}) => {
    try { await run(`/admin/endorsements/${id}/${action}`, { method: 'POST', body }); toast.push(action === 'approve' ? 'Đã duyệt.' : 'Đã từ chối.'); reload(); }
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
                <tr key={e.endorsement_request_id}>
                  <td className="mono" style={{ fontSize: '0.78rem' }}>{e.endorsement_request_id.slice(0, 8)}</td>
                  <td className="mono" style={{ fontSize: '0.78rem' }}>{e.policy_id.slice(0, 8)}</td>
                  <td className="muted">{dateTime(e.effective_date)}</td>
                  <td className="num">{vndLabel(e.difference_vnd)}</td>
                  <td><StatusPill status={e.status} /></td>
                  <td className="num">
                    {e.status === 'PENDING_REVIEW' && (
                      <div className="row" style={{ justifyContent: 'flex-end' }}>
                        <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => act(e.endorsement_request_id, 'approve')}>Duyệt</button>
                        <button className="btn btn-danger btn-sm" disabled={busy} onClick={() => act(e.endorsement_request_id, 'reject')}>Từ chối</button>
                      </div>
                    )}
                    {e.status === 'APPROVED' && (
                      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => extend(e.endorsement_request_id)}>+7 ngày</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && <Pager page={page} totalPages={data.total_pages} total={data.total_elements} onPage={setPage} />}
    </div>
  );
}
