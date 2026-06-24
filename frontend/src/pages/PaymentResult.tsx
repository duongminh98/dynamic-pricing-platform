import { useState, useEffect } from 'react';
import { apiFetch } from '../api/client';

export default function PaymentResult() {
  const params = new URLSearchParams(window.location.search);
  const txnRef = params.get('vnp_TxnRef') || '';
  const responseCode = params.get('vnp_ResponseCode') || '';
  const [status, setStatus] = useState<string>('confirming');
  const [details, setDetails] = useState<any>(null);
  const [error, setError] = useState<string>('');

  // Initial status from Return URL params (display only, not authoritative)
  useEffect(() => {
    if (responseCode === '00') {
      setStatus('confirming');
    } else if (responseCode) {
      setStatus('failed');
    }
  }, [responseCode]);

  // Poll payment status until IPN confirms (task 21.5)
  useEffect(() => {
    if (!txnRef || status === 'failed') return;
    let cancelled = false;
    const poll = async () => {
      for (let i = 0; i < 30 && !cancelled; i++) {
        try {
          const r = await apiFetch(`/billing/vnpay/status?vnp_txn_ref=${encodeURIComponent(txnRef)}`);
          if (cancelled) return;
          setDetails(r);
          if (r.status === 'success') { setStatus('success'); return; }
          if (r.status === 'failed') { setStatus('failed'); return; }
        } catch {
          // ignore transient errors during polling
        }
        await new Promise(resolve => setTimeout(resolve, 2000));
      }
      if (!cancelled && status === 'confirming') setError('Timed out waiting for payment confirmation');
    };
    poll();
    return () => { cancelled = true; };
  }, [txnRef, status]);

  return (
    <div>
      <h2>Payment Result</h2>
      {status === 'confirming' && <p>Confirming your payment, please wait...</p>}
      {status === 'success' && <p style={{ color: 'green' }}>Payment successful! Your policy is being issued.</p>}
      {status === 'failed' && <p style={{ color: 'red' }}>Payment failed or was cancelled.</p>}
      {error && <p style={{ color: 'orange' }}>{error}</p>}
      {details && (
        <div>
          <p>Transaction Ref: {details.vnp_txn_ref}</p>
          <p>Amount: {Number(details.amount_vnd).toLocaleString()} VND</p>
          <p>Status: {details.status}</p>
        </div>
      )}
      <a href='/policies'>Back to Policies</a>
    </div>
  );
}
