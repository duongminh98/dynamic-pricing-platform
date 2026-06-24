import { useState } from 'react';
import { apiFetch } from '../api/client';

export default function Checkout() {
  const [invoiceId, setInvoiceId] = useState('');
  const [paymentUrl, setPaymentUrl] = useState<string | null>(null);
  const [error, setError] = useState('');

  const createPaymentUrl = async () => {
    setError('');
    setPaymentUrl(null);
    try {
      const r = await apiFetch(`/billing/invoices/${invoiceId}/payment-url`, { method: 'POST' });
      setPaymentUrl(r.payment_url);
    } catch {
      setError('Failed to create payment URL. Make sure the invoice ID is valid and unpaid.');
    }
  };

  return (
    <div>
      <h2>Checkout ? Pay Invoice via VNPAY</h2>
      <p>Enter your invoice ID to generate a VNPAY payment URL.</p>
      <input
        value={invoiceId}
        onChange={e => setInvoiceId(e.target.value)}
        placeholder='Invoice UUID'
        style={{ width: '350px' }}
      />
      <button onClick={createPaymentUrl} disabled={!invoiceId}>Get Payment URL</button>
      {error && <p style={{ color: 'red' }}>{error}</p>}
      {paymentUrl && (
        <div>
          <p>Click the link below to pay via VNPAY sandbox:</p>
          <a href={paymentUrl} target='_blank' rel='noopener noreferrer'>Pay with VNPAY</a>
        </div>
      )}
    </div>
  );
}
