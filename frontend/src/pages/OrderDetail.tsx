import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { apiFetch, ApiError } from '../api/client';
import { useApi, useMutation, useInterval, vndLabel, dateTime } from '../lib/format';
import { viProduct } from '../lib/labels';
import { Loading, ErrorBanner, StatusPill, Spinner, Alert, useToast } from '../lib/ui';

interface OrderResponse {
  order_id: string; quote_id: string; product_id: string; final_premium_vnd: number;
  status: string; review_decision: string | null; review_reason: string | null;
  reviewed_at: string | null; created_at: string; invoice_id: string | null;
}
interface InvoiceResponse {
  invoice_id: string; order_id: string; amount_vnd: number; status: string; paid_at: string | null;
}

export default function OrderDetail() {
  const { id } = useParams();
  const nav = useNavigate();
  const toast = useToast();
  const { data: order, error, loading, reload } = useApi<OrderResponse>(`/orders/${id}`, [id]);
  const [invoice, setInvoice] = useState<InvoiceResponse | null>(null);
  const { run, busy } = useMutation();

  // While approved but invoice not yet linked, poll until billing finishes.
  const awaitingInvoice = order?.status === 'PENDING_PAYMENT' && !order?.invoice_id;
  useInterval(() => reload(), awaitingInvoice ? 3000 : null);

  // Once we have an invoice id, fetch the invoice details.
  useEffect(() => {
    if (order?.invoice_id) {
      apiFetch<InvoiceResponse>(`/billing/invoices/by-order/${order.order_id}`).then(setInvoice).catch(() => {});
    }
  }, [order?.invoice_id, order?.order_id]);

  const pay = async () => {
    if (!order?.invoice_id) return;
    try {
      const res = await run<{ payment_url: string; vnp_txn_ref: string }>(
        `/billing/invoices/${order.invoice_id}/payment-url`, { method: 'POST', body: {} },
      );
      sessionStorage.setItem('vnp_txn_ref', res.vnp_txn_ref);
      sessionStorage.setItem('vnp_order_id', order.order_id);
      sessionStorage.removeItem('vnp_policy_id');
      window.location.href = res.payment_url;
    } catch (e) {
      const err = e as ApiError;
      if (err.code === 'INVOICE_NOT_PAYABLE') { toast.push('Hóa đơn không còn ở trạng thái chờ thanh toán.', 'warn'); reload(); }
      else if (err.code === 'SERVICE_UNAVAILABLE') toast.push('Cổng thanh toán tạm thời không khả dụng. Thử lại sau.', 'warn');
      else toast.push(err.message, 'err');
    }
  };

  if (loading) return <Loading />;
  if (error) return <ErrorBanner error={error} />;
  if (!order) return null;

  return (
    <div className="stack" style={{ maxWidth: 680 }}>
      <Link to="/orders" className="btn-link">← Đơn hàng của tôi</Link>

      <div className="card stack">
        <div className="row-between">
          <div>
            <span className="mono faint" style={{ fontSize: '0.78rem' }}>{order.order_id}</span>
            <h3 style={{ fontSize: 'var(--step-2)', marginTop: 4 }}>{viProduct(order.product_id)}</h3>
          </div>
          <StatusPill status={order.status} />
        </div>

        <div className="panel">
          <div className="kv" style={{ borderTop: 'none' }}><span className="kv-k">Phí bảo hiểm</span><span className="kv-v">{vndLabel(order.final_premium_vnd)}</span></div>
          <div className="kv"><span className="kv-k">Ngày tạo</span><span className="kv-v">{dateTime(order.created_at)}</span></div>
          {order.reviewed_at && <div className="kv"><span className="kv-k">Đã duyệt lúc</span><span className="kv-v">{dateTime(order.reviewed_at)}</span></div>}
        </div>

        {order.status === 'PENDING_REVIEW' && (
          <Alert kind="info">Đơn hàng đang chờ quản trị viên duyệt. Bạn sẽ nhận thông báo khi có kết quả.</Alert>
        )}
        {order.status === 'REJECTED' && (
          <Alert kind="err">Đơn hàng bị từ chối{order.review_reason ? `: ${order.review_reason}` : '.'}</Alert>
        )}
        {awaitingInvoice && (
          <Alert kind="warn"><span className="row"><Spinner /> <span style={{ marginLeft: 8 }}>Đơn đã được duyệt. Đang tạo hóa đơn…</span></span></Alert>
        )}

        {order.status === 'PENDING_PAYMENT' && order.invoice_id && (
          <div className="stack">
            {invoice && invoice.status === 'paid' ? (
              <Alert kind="ok">Hóa đơn đã được thanh toán.</Alert>
            ) : (
              <>
                <Alert kind="info">Đơn đã được duyệt. Thanh toán để phát hành hợp đồng.</Alert>
                <button className="btn btn-primary btn-block" disabled={busy} onClick={pay}>
                  {busy ? <Spinner /> : 'Thanh toán qua VNPAY →'}
                </button>
              </>
            )}
          </div>
        )}

        {order.status === 'COMPLETED' && (
          <Alert kind="ok">Thanh toán hoàn tất, hợp đồng đã được phát hành. <Link to="/policies">Xem hợp đồng →</Link></Alert>
        )}
      </div>
    </div>
  );
}
