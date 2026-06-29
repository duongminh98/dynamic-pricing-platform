import { useState } from 'react';
import { ApiError } from '../../api/client';
import { useMutation, vndLabel, dateTime } from '../../lib/format';
import { viStatus } from '../../lib/labels';
import { usePaged, Pager } from '../../lib/paged';
import { Loading, ErrorBanner, EmptyState, StatusPill, Spinner, useToast } from '../../lib/ui';

interface InvoiceResponse {
  invoice_id: string; order_id: string; policy_id: string | null;
  amount_vnd: number; status: string; paid_at: string | null; created_at: string;
}

const STATUSES = ['unpaid', 'paid', 'voided'];

export default function AdminBilling() {
  const toast = useToast();
  const [status, setStatus] = useState('');
  const { run, busy } = useMutation();
  const { data, error, loading, page, setPage, reload } = usePaged<InvoiceResponse>('/admin/billing/invoices', { status });

  const voidInvoice = async (id: string) => {
    try { await run(`/admin/billing/invoices/${id}/void`, { method: 'POST', body: {} }); toast.push('Đã hủy hóa đơn.'); reload(); }
    catch (e) {
      const err = e as ApiError;
      if (err.code === 'BAD_REQUEST') toast.push('Hóa đơn đã thanh toán, không thể hủy.', 'warn');
      else toast.push(err.message, 'err');
    }
  };

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Tài chính</p>
        <h2>Hóa đơn</h2>
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
      {data && data.content.length === 0 && <EmptyState title="Không có hóa đơn" />}

      {data && data.content.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Mã HĐ</th><th>Đơn / Hợp đồng</th><th>Số tiền</th><th>Trạng thái</th><th>Tạo lúc</th><th></th></tr></thead>
            <tbody>
              {data.content.map((inv) => (
                <tr key={inv.invoice_id}>
                  <td className="mono" style={{ fontSize: '0.78rem' }}>{inv.invoice_id.slice(0, 8)}</td>
                  <td className="mono" style={{ fontSize: '0.78rem' }}>{(inv.policy_id || inv.order_id || '').slice(0, 8)}</td>
                  <td className="num">{vndLabel(inv.amount_vnd)}</td>
                  <td><StatusPill status={inv.status} /></td>
                  <td className="muted">{dateTime(inv.created_at)}</td>
                  <td className="num">
                    {inv.status === 'unpaid' && (
                      <button className="btn btn-danger btn-sm" disabled={busy} onClick={() => voidInvoice(inv.invoice_id)}>{busy ? <Spinner /> : 'Hủy HĐ'}</button>
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
