import { useEffect, useState } from 'react';
import { apiFetch } from '../../api/client';

export default function AdminConsole() {
  const [queue, setQueue] = useState<any[]>([]);
  useEffect(() => { apiFetch('/admin/orders/review-queue').then(setQueue).catch(() => {}); }, []);
  const approve = async (id: string) => { await apiFetch('/admin/orders/' + id + '/approve', { method: 'POST' }); setQueue(queue.filter(o => o.orderId !== id)); };
  return <div><h2>Admin - Order Review</h2><ul>{queue.map(o => <li key={o.orderId}>{o.orderId} - {o.productId} - {o.finalPremiumVnd} VND <button onClick={() => approve(o.orderId)}>Approve</button></li>)}</ul></div>;
}
