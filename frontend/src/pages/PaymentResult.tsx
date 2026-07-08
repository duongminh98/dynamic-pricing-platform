import { useEffect, useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { useInterval, vndLabel } from '../lib/format';
import { Spinner, Alert } from '../lib/ui';

interface StatusResp {
  vnp_txn_ref: string; status: 'pending' | 'success' | 'failed';
  amount_vnd: string; vnp_response_code: string; vnp_transaction_no: string;
}

/** VNPAY redirects the browser here. The Return URL is display-only; the
 * real result is confirmed by polling /billing/vnpay/status (IPN is source
 * of truth server-side). */
export default function PaymentResult() {
  const [params] = useSearchParams();
  const nav = useNavigate();
  const txnRef = params.get('vnp_txn_ref') || sessionStorage.getItem('vnp_txn_ref') || '';
  const orderId = sessionStorage.getItem('vnp_order_id');
  const policyId = sessionStorage.getItem('vnp_policy_id');

  const [status, setStatus] = useState<'pending' | 'success' | 'failed' | 'unknown'>('pending');
  const [amount, setAmount] = useState<string | null>(null);
  const [tries, setTries] = useState(0);

  const poll = async () => {
    if (!txnRef || status === 'success' || status === 'failed') return;
    try {
      const r = await apiFetch<StatusResp>(`/billing/vnpay/status?vnp_txn_ref=${encodeURIComponent(txnRef)}`);
      setAmount(r.amount_vnd);
      if (r.status !== 'pending') setStatus(r.status);
    } catch {
      setStatus('unknown');
    }
    setTries((t) => t + 1);
  };

  const confirmReturn = async () => {
    const vnpTxnRef = params.get('vnp_TxnRef');
    if (!vnpTxnRef) return;
    const query = params.toString();
    try {
      const r = await apiFetch<{ status: 'success' | 'failed' | 'invalid'; amount_vnd?: string }>(`/billing/vnpay/return?${query}`);
      if (r.amount_vnd) setAmount(r.amount_vnd);
      if (r.status === 'success' || r.status === 'failed') setStatus(r.status);
      if (r.status === 'invalid') setStatus('unknown');
    } catch {
      setStatus('unknown');
    }
  };

  useEffect(() => { confirmReturn().then(poll); /* eslint-disable-next-line */ }, []);
  // poll every 2.5s for up to ~50s, then stop nudging
  useInterval(poll, status === 'pending' && tries < 20 ? 2500 : null);

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 'var(--s6)' }}>
      <div className="card card-pad-lg stack center" style={{ maxWidth: 460, textAlign: 'center' }}>
        <div className="brand center" style={{ justifyContent: 'center', marginBottom: 0 }}>
          <span className="brand-mark">DPP</span>
        </div>

        {status === 'pending' && (
          <>
            <Spinner lg />
            <h3>Đang xác nhận thanh toán…</h3>
            <p className="muted">Chúng tôi đang xác minh với VNPAY. Vui lòng không đóng trang.</p>
          </>
        )}
        {status === 'success' && (
          <>
            <div style={{ fontSize: '3rem', color: 'var(--jade)' }}>✓</div>
            <h3>Thanh toán thành công</h3>
            {amount && <p className="figure" style={{ fontSize: 'var(--step-2)' }}>{vndLabel(amount)}</p>}
            <p className="muted">{orderId
              ? 'Hợp đồng của bạn đang được phát hành. Bạn sẽ nhận thông báo ngay khi hoàn tất.'
              : 'Yêu cầu của bạn đang được xử lý. Bạn sẽ nhận thông báo ngay khi hoàn tất.'}</p>
          </>
        )}
        {status === 'failed' && (
          <>
            <div style={{ fontSize: '3rem', color: 'var(--terra)' }}>✕</div>
            <h3>Thanh toán không thành công</h3>
            <p className="muted">Giao dịch chưa hoàn tất. Bạn có thể thử thanh toán lại từ đơn hàng.</p>
          </>
        )}
        {status === 'unknown' && (
          <Alert kind="warn">Không xác định được trạng thái thanh toán. Vui lòng kiểm tra lại đơn hàng.</Alert>
        )}

        <div className="row" style={{ justifyContent: 'center', marginTop: 'var(--s3)' }}>
          {orderId && <button className="btn btn-ghost" onClick={() => nav(`/orders/${orderId}`)}>Xem đơn hàng</button>}
          {policyId && <button className="btn btn-primary" onClick={() => nav(`/policies/${policyId}`)}>Xem hợp đồng</button>}
          {!policyId && <button className="btn btn-primary" onClick={() => nav('/policies')}>Hợp đồng của tôi</button>}
        </div>
      </div>
    </div>
  );
}
