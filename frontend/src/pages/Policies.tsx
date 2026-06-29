import { useNavigate } from 'react-router-dom';
import { useApi, vndLabel, dateOnly } from '../lib/format';
import { LINE_LABEL, LINE_ICON, Line } from '../lib/domain';
import { viProduct } from '../lib/labels';
import { Loading, ErrorBanner, EmptyState, StatusPill } from '../lib/ui';

interface PolicyResponse {
  policy_id: string; product_id: string; line: string | null; status: string;
  policy_effective_date: string; policy_expiration_date: string;
  renewal_number: number; final_premium_vnd: number;
}

export default function Policies() {
  const nav = useNavigate();
  const { data, error, loading } = useApi<PolicyResponse[]>('/policies');

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Hợp đồng</p>
        <h2>Hợp đồng của tôi</h2>
      </div>
      {loading && <Loading />}
      <ErrorBanner error={error} />
      {data && data.length === 0 && <EmptyState mark="⛨" title="Bạn chưa có hợp đồng nào" hint="Hợp đồng được phát hành tự động sau khi thanh toán thành công." />}

      {data && data.length > 0 && (
        <div className="cards-grid">
          {data.map((p) => (
            <article key={p.policy_id} className="card prod-card" role="button" tabIndex={0}
              onClick={() => nav(`/policies/${p.policy_id}`)} onKeyDown={(e) => e.key === 'Enter' && nav(`/policies/${p.policy_id}`)}>
              <div className="row-between">
                <span className="prod-line">{p.line ? `${LINE_ICON[p.line as Line]} ${LINE_LABEL[p.line as Line]}` : '—'}</span>
                <StatusPill status={p.status} />
              </div>
              <div className="prod-name">{viProduct(p.product_id)}</div>
              <div style={{ marginTop: 'auto' }}>
                <div className="kv"><span className="kv-k">Phí</span><span className="kv-v">{vndLabel(p.final_premium_vnd)}</span></div>
                <div className="kv"><span className="kv-k">Hiệu lực</span><span className="kv-v">{dateOnly(p.policy_effective_date)}</span></div>
                <div className="kv"><span className="kv-k">Hết hạn</span><span className="kv-v">{dateOnly(p.policy_expiration_date)}</span></div>
                {p.renewal_number > 0 && <div className="kv"><span className="kv-k">Tái tục lần</span><span className="kv-v">#{p.renewal_number}</span></div>}
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
