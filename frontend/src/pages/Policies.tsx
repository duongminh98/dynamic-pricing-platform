import { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';

export default function Policies() {
  const [policies, setPolicies] = useState<any[]>([]);
  useEffect(() => { apiFetch('/policies').then(setPolicies).catch(() => {}); }, []);
  return <div><h2>My Policies</h2><ul>{policies.map((p, i) => <li key={i}>{p.policyId} - {p.status} - {p.finalPremiumVnd} VND</li>)}</ul></div>;
}
