import { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';

export default function Products() {
  const [products, setProducts] = useState<any[]>([]);
  useEffect(() => { apiFetch('/products').then(setProducts).catch(() => {}); }, []);
  return <div><h2>Products</h2><ul>{products.map((p, i) => <li key={i}>{p.product_id} - {p.product_name}</li>)}</ul></div>;
}
