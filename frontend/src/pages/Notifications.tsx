import { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';

interface Notification {
  notification_id: string;
  type: string;
  message: string;
  status: string;
  created_at: string;
}

export default function Notifications() {
  const [items, setItems] = useState<Notification[]>([]);
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');

  const load = async () => {
    try {
      const q = status ? `?status=${status}` : '';
      setItems(await apiFetch('/notifications' + q));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  useEffect(() => { load(); }, [status]);

  return (
    <div>
      <h2>Notifications</h2>
      <label>
        Filter:{' '}
        <select value={status} onChange={e => setStatus(e.target.value)}>
          <option value=''>all</option>
          <option value='sent'>sent</option>
          <option value='failed'>failed</option>
          <option value='pending'>pending</option>
        </select>
      </label>
      {error && <p>{error}</p>}
      <ul>
        {items.map(n => (
          <li key={n.notification_id}>
            <strong>{n.type}</strong> [{n.status}] - {n.message} ({n.created_at})
          </li>
        ))}
      </ul>
    </div>
  );
}
