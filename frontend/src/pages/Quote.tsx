import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { apiFetch, ApiError } from '../api/client';
import { useApi, useMutation, vndLabel } from '../lib/format';
import { viFeature, viProduct } from '../lib/labels';
import { LINE_LABEL, LINE_ICON, Line } from '../lib/domain';
import { SelectField } from '../lib/fields';
import { Loading, ErrorBanner, Spinner, Alert, useToast } from '../lib/ui';

interface ProductSummary { product_id: string; line: string; product_name: string; }
interface BaseProfile {
  age: number; gender: string; province: string; region: string; urban_tier: string;
  occupation: string; income_level: string; marital_status: string;
  lines?: { line: string; line_attributes: Record<string, any> }[];
}
interface ExplItem { feature: string; label: string; direction: 'increase' | 'decrease'; magnitude: number; }
interface Explanation { available: boolean; method?: string; items: ExplItem[]; components?: Record<string, Explanation>; }
interface QuoteResult {
  quote_id: string; line: string; product_id: string;
  coverage_amount_vnd: number; deductible_vnd: number;
  frequency?: number | null; severity?: number | null;
  pure_premium_vnd: number; final_premium_vnd: number; admin_fee_vnd: number;
  loading_factor: number; expires_at: string;
  explanation: Explanation;
  model_version: string;
}

export default function Quote() {
  const [params] = useSearchParams();
  const nav = useNavigate();
  const toast = useToast();
  const { data: products } = useApi<ProductSummary[]>('/products');
  const [productId, setProductId] = useState(params.get('product') || '');
  const [profile, setProfile] = useState<BaseProfile | null>(null);
  const [profileMissing, setProfileMissing] = useState(false);
  const [loadingProfile, setLoadingProfile] = useState(true);
  const [result, setResult] = useState<QuoteResult | null>(null);
  const { run, busy, error } = useMutation();

  useEffect(() => {
    apiFetch<BaseProfile>('/customers/me/profile')
      .then(setProfile)
      .catch((e: ApiError) => { if (e.code === 'RESOURCE_NOT_FOUND') setProfileMissing(true); })
      .finally(() => setLoadingProfile(false));
  }, []);

  const selected = products?.find((p) => p.product_id === productId);
  const lineAttrs = selected ? profile?.lines?.find((l) => l.line === selected.line)?.line_attributes : undefined;

  const getQuote = async () => {
    if (!profile || !selected) return;
    const body = {
      product_id: productId,
      profile: {
        age: profile.age, gender: profile.gender, province: profile.province,
        region: profile.region, urban_tier: profile.urban_tier, occupation: profile.occupation,
        income_level: profile.income_level, marital_status: profile.marital_status,
        line_attributes: lineAttrs || {},
      },
    };
    try {
      const r = await run<QuoteResult>('/pricing/quote', { method: 'POST', body });
      setResult(r);
    } catch { /* shown below */ }
  };

  const placeOrder = async () => {
    if (!result) return;
    try {
      const order = await run<{ order_id: string }>('/orders', { method: 'POST', body: { quote_id: result.quote_id } });
      toast.push('Đã gửi đơn hàng. Đang chờ duyệt.');
      nav(`/orders/${order.order_id}`);
    } catch (e) {
      const err = e as ApiError;
      if (err.code === 'QUOTE_EXPIRED') toast.push('Báo giá đã hết hạn, vui lòng báo giá lại.', 'warn');
      else if (err.code === 'QUOTE_ALREADY_USED') toast.push('Báo giá này đã được dùng cho một đơn khác.', 'warn');
      else if (err.code === 'DUPLICATE_ACTIVE_POLICY') toast.push('Bạn đã có hợp đồng đang hiệu lực cho tài sản này.', 'warn');
    }
  };

  if (loadingProfile) return <Loading />;

  if (profileMissing) {
    return (
      <div className="stack" style={{ maxWidth: 560 }}>
        <Alert kind="warn">Bạn cần hoàn thiện hồ sơ rủi ro trước khi nhận báo giá.</Alert>
        <button className="btn btn-primary" onClick={() => nav('/profile')}>Hoàn thiện hồ sơ →</button>
      </div>
    );
  }

  const lineMissing = selected && !lineAttrs;

  return (
    <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 380px) 1fr', alignItems: 'start', gap: 'var(--s6)' }}>
      <div className="card stack">
        <div>
          <p className="eyebrow">Bước 1</p>
          <h3 style={{ fontSize: 'var(--step-1)' }}>Chọn sản phẩm</h3>
        </div>
        <SelectField
          label="Sản phẩm"
          value={productId}
          onChange={(v) => { setProductId(v); setResult(null); }}
          options={(products || []).map((p) => p.product_id)}
          labelFn={(id) => { const p = products?.find((x) => x.product_id === id); return p ? `${LINE_ICON[p.line as Line]} ${viProduct(p.product_id, p.product_name)}` : id; }}
          placeholder="Chọn sản phẩm…"
        />

        {selected && (
          <div className="panel">
            <div className="row-between" style={{ marginBottom: 8 }}>
              <span className="prod-line">{LINE_ICON[selected.line as Line]} {LINE_LABEL[selected.line as Line]}</span>
            </div>
            {lineMissing ? (
              <Alert kind="warn">
                Chưa có thuộc tính hồ sơ cho dòng {LINE_LABEL[selected.line as Line].toLowerCase()}.{' '}
                <button className="btn-link" onClick={() => nav('/profile')}>Bổ sung ngay</button>
              </Alert>
            ) : (
              <p className="field-hint" style={{ margin: 0 }}>Báo giá dùng hồ sơ {LINE_LABEL[selected.line as Line].toLowerCase()} đã lưu của bạn.</p>
            )}
          </div>
        )}

        <ErrorBanner error={result ? null : error} />

        <button className="btn btn-primary" disabled={!productId || busy || !!lineMissing} onClick={getQuote}>
          {busy && !result ? <Spinner /> : 'Tính phí cho tôi'}
        </button>
      </div>

      <div>
        {!result ? (
          <div className="card price-hero" style={{ minHeight: 280, display: 'grid', placeItems: 'center', textAlign: 'center' }}>
            <div>
              <div className="price-label">Mức phí của bạn</div>
              <div className="price-amount" style={{ opacity: 0.35 }}>— — —<span className="cur">₫</span></div>
              <p style={{ color: '#7E887E', marginTop: 16, maxWidth: 360 }}>Chọn sản phẩm và bấm tính phí. Chúng tôi sẽ phân rã từng yếu tố tạo nên con số này.</p>
            </div>
          </div>
        ) : (
          <QuoteHero result={result} onOrder={placeOrder} ordering={busy} />
        )}
      </div>
    </div>
  );
}

function QuoteHero({ result, onOrder, ordering }: { result: QuoteResult; onOrder: () => void; ordering: boolean }) {
  const frequencyExpl = result.explanation?.components?.frequency;
  const severityExpl = result.explanation?.components?.severity;
  const fallbackExpl = result.explanation?.components ? undefined : result.explanation;

  return (
    <div className="stack">
      <div className="price-hero">
        <div className="row-between">
          <div className="price-label">Your premium</div>
          <span className="tag mono" style={{ background: 'rgba(255,255,255,.06)', color: '#9AA39A', borderColor: 'rgba(255,255,255,.1)' }}>{result.model_version}</span>
        </div>
        <div className="price-amount">{vndLabel(result.final_premium_vnd).replace(' ₫', '')}<span className="cur">₫ / year</span></div>

        {frequencyExpl || severityExpl ? (
          <>
            {frequencyExpl && <ExplanationBlock title="Claim frequency drivers" explanation={frequencyExpl} />}
            {severityExpl && <ExplanationBlock title="Claim severity drivers" explanation={severityExpl} />}
            <div className="decomp-legend">
              <span><span className="legend-dot" style={{ background: 'var(--terra)' }} />Increases premium</span>
              <span><span className="legend-dot" style={{ background: '#2FB89E' }} />Decreases premium</span>
            </div>
            <p className="field-hint" style={{ marginTop: 8 }}>So với mức trung bình của danh mục khách hàng.</p>
          </>
        ) : fallbackExpl ? (
          <ExplanationBlock title="Quote drivers" explanation={fallbackExpl} />
        ) : (
          <p style={{ color: '#7E887E', marginTop: 20 }}>Detailed breakdown is unavailable for this quote, but the premium is still valid.</p>
        )}
      </div>

      <div className="card">
        <div className="stat-row">
          <div className="stat"><span className="stat-l">Predicted claim frequency</span><span className="stat-n">{result.frequency == null ? '-' : result.frequency.toFixed(4)}</span></div>
          <div className="stat"><span className="stat-l">Predicted severity per claim</span><span className="stat-n">{result.severity == null ? '-' : vndLabel(result.severity)}</span></div>
          <div className="stat"><span className="stat-l">Pure risk premium</span><span className="stat-n">{vndLabel(result.pure_premium_vnd)}</span></div>
          <div className="stat"><span className="stat-l">Loading factor</span><span className="stat-n">×{result.loading_factor}</span></div>
          <div className="stat"><span className="stat-l">Admin fee</span><span className="stat-n">{vndLabel(result.admin_fee_vnd)}</span></div>
        </div>
        <hr className="divider" />
        <div className="kv" style={{ borderTop: 'none' }}><span className="kv-k">Coverage amount</span><span className="kv-v">{vndLabel(result.coverage_amount_vnd)}</span></div>
        <div className="kv"><span className="kv-k">Deductible</span><span className="kv-v">{vndLabel(result.deductible_vnd)}</span></div>
        <p className="field-hint" style={{ marginTop: 12 }}>Quote valid until {new Date(result.expires_at).toLocaleDateString('vi-VN')}.</p>
      </div>

      <button className="btn btn-primary btn-block" disabled={ordering} onClick={onOrder}>
        {ordering ? <Spinner /> : 'Place order with this premium →'}
      </button>
    </div>
  );
}

function ExplanationBlock({ title, explanation }: { title: string; explanation: Explanation }) {
  // magnitude is a signed fraction (0.32 == +32%, -0.18 == -18%): the feature's
  // multiplicative effect on this component vs the portfolio average. Rank and
  // size bars by absolute effect; the sign/direction drives colour and label.
  const items = explanation?.available
    ? [...(explanation.items || [])].sort((a, b) => Math.abs(b.magnitude) - Math.abs(a.magnitude)).slice(0, 6)
    : [];
  const maxMag = items.reduce((m, it) => Math.max(m, Math.abs(it.magnitude)), 0) || 1;
  if (!items.length) return null;
  return (
    <div className="decomp" style={{ marginTop: 18 }}>
      <p className="eyebrow" style={{ marginBottom: 10 }}>{title}</p>
      {items.map((it) => (
        <div className="decomp-row" key={`${title}-${it.feature}`}>
          <span className="decomp-feat">{viFeature(it.feature)}</span>
          <div className="decomp-track">
            <span className="decomp-mid" />
            <span
              className={'decomp-fill ' + (it.direction === 'increase' ? 'up' : 'down')}
              style={{ width: `${Math.max(4, (Math.abs(it.magnitude) / maxMag) * 48)}%` }}
            />
          </div>
          <span className="decomp-mag">{it.direction === 'increase' ? '+' : '−'}{Math.round(Math.abs(it.magnitude) * 100)}%</span>
        </div>
      ))}
    </div>
  );
}