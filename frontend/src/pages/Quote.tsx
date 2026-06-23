import { useState } from 'react';
import { apiFetch } from '../api/client';

export default function Quote() {
  const [productId, setProductId] = useState('HEALTH_BASIC');
  const [result, setResult] = useState<any>(null);
  const quote = async () => {
    const r = await apiFetch('/pricing/quote', { method: 'POST', body: JSON.stringify({ product_id: productId, profile: {} }) });
    setResult(r);
  };
  return <div><h2>Get Quote</h2><input value={productId} onChange={e => setProductId(e.target.value)} /><button onClick={quote}>Quote</button>{result && <div><p>Final Premium: {result.final_premium_vnd} VND</p>{result.explanation && <ul>{result.explanation.items?.map((it: any, i: number) => <li key={i}>{it.label}: {it.direction} {it.magnitude}</li>)}</ul>}</div>}</div>;
}
