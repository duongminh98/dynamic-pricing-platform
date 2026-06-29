import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useApi } from '../lib/format';
import { vndLabel } from '../lib/format';
import { LINES, LINE_LABEL, LINE_ICON, Line } from '../lib/domain';
import { viProduct } from '../lib/labels';
import { Loading, ErrorBanner, EmptyState } from '../lib/ui';

interface ProductSummary {
  product_id: string;
  line: string;
  product_name: string;
  coverage_amount_vnd: number;
  deductible_vnd: number;
}

export default function Products() {
  const nav = useNavigate();
  const [line, setLine] = useState<Line | ''>('');
  const path = line ? `/products?line=${line}` : '/products';
  const { data, error, loading } = useApi<ProductSummary[]>(path, [line]);

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Danh mục bảo hiểm</p>
        <h2>Chọn dòng bảo hiểm phù hợp với bạn</h2>
        <p className="muted" style={{ maxWidth: 560, marginTop: 8 }}>
          Giá hiển thị là phí tham chiếu. Mức phí thực tế được tính riêng theo hồ sơ rủi ro của bạn ở bước báo giá.
        </p>
      </div>

      <div className="tabs">
        <button className={'tab' + (line === '' ? ' active' : '')} onClick={() => setLine('')}>
          Tất cả
        </button>
        {LINES.map((l) => (
          <button key={l} className={'tab' + (line === l ? ' active' : '')} onClick={() => setLine(l)}>
            {LINE_ICON[l]} {LINE_LABEL[l]}
          </button>
        ))}
      </div>

      {loading && <Loading />}
      <ErrorBanner error={error} />
      {data && data.length === 0 && <EmptyState title="Chưa có sản phẩm cho dòng này" />}

      {data && data.length > 0 && (
        <div className="cards-grid">
          {data.map((p) => (
            <article key={p.product_id} className="card prod-card" onClick={() => nav(`/products/${p.product_id}`)} role="button" tabIndex={0}
              onKeyDown={(e) => e.key === 'Enter' && nav(`/products/${p.product_id}`)}>
              <div className="row-between">
                <span className="prod-line">{LINE_ICON[p.line as Line]} {LINE_LABEL[p.line as Line] || p.line}</span>
                <span className="tag mono">{p.product_id}</span>
              </div>
              <div className="prod-name">{viProduct(p.product_id, p.product_name)}</div>
              <div style={{ marginTop: 'auto' }}>
                <div className="kv">
                  <span className="kv-k">Số tiền bảo hiểm</span>
                  <span className="kv-v">{vndLabel(p.coverage_amount_vnd)}</span>
                </div>
                <div className="kv">
                  <span className="kv-k">Mức miễn thường</span>
                  <span className="kv-v">{vndLabel(p.deductible_vnd)}</span>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
