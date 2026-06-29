import { useState } from 'react';
import { ApiError } from '../../api/client';
import { useApi, useMutation, vndLabel, dateTime } from '../../lib/format';
import { LINES, LINE_LABEL, Line } from '../../lib/domain';
import { Loading, ErrorBanner, EmptyState, Spinner, useToast } from '../../lib/ui';
import { TextField, NumberField, SelectField, Toggle } from '../../lib/fields';

interface ProductSummary {
  product_id: string; line: string; product_name: string;
  coverage_amount_vnd: number; deductible_vnd: number;
}
interface RateVersion {
  rate_version_id: string; effective_at: string; created_by: string; is_current: boolean; created_at: string;
}

export default function AdminCatalog() {
  const [tab, setTab] = useState<'products' | 'rates'>('products');
  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Cấu hình định giá</p>
        <h2>Danh mục & hệ số giá</h2>
      </div>
      <div className="tabs">
        <button className={'tab' + (tab === 'products' ? ' active' : '')} onClick={() => setTab('products')}>Sản phẩm</button>
        <button className={'tab' + (tab === 'rates' ? ' active' : '')} onClick={() => setTab('rates')}>Hệ số tải & phiên bản</button>
      </div>
      {tab === 'products' ? <ProductsTab /> : <RatesTab />}
    </div>
  );
}

/* ---------- products ---------- */
function ProductsTab() {
  const toast = useToast();
  const { data, error, loading, reload } = useApi<ProductSummary[]>('/products');
  const [editing, setEditing] = useState<ProductSummary | 'new' | null>(null);

  return (
    <div className="stack">
      <div className="row-between">
        <span className="muted">{data?.length ?? 0} sản phẩm đang hoạt động</span>
        <button className="btn btn-primary btn-sm" onClick={() => setEditing('new')}>+ Sản phẩm mới</button>
      </div>

      {editing && <ProductForm initial={editing === 'new' ? null : editing} onDone={() => { setEditing(null); reload(); toast.push('Đã lưu sản phẩm.'); }} onCancel={() => setEditing(null)} />}

      {loading && <Loading />}
      <ErrorBanner error={error} />
      {data && data.length === 0 && <EmptyState title="Chưa có sản phẩm" />}

      {data && data.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Mã</th><th>Tên</th><th>Dòng</th><th>STBH</th><th>Miễn thường</th><th></th></tr></thead>
            <tbody>
              {data.map((p) => (
                <tr key={p.product_id}>
                  <td className="mono">{p.product_id}</td>
                  <td>{p.product_name}</td>
                  <td className="muted">{LINE_LABEL[p.line as Line] || p.line}</td>
                  <td className="num">{vndLabel(p.coverage_amount_vnd)}</td>
                  <td className="num">{vndLabel(p.deductible_vnd)}</td>
                  <td className="num"><button className="btn btn-ghost btn-sm" onClick={() => setEditing(p)}>Sửa</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function ProductForm({ initial, onDone, onCancel }: { initial: ProductSummary | null; onDone: () => void; onCancel: () => void }) {
  const toast = useToast();
  const { run, busy, error } = useMutation();
  const [form, setForm] = useState(() => ({
    product_id: initial?.product_id || '',
    category: (initial?.line as string) || 'health',
    product_name: initial?.product_name || '',
    coverage_amount_vnd: (initial?.coverage_amount_vnd ?? '') as number | '',
    deductible_vnd: (initial?.deductible_vnd ?? '') as number | '',
    base_premium_vnd: '' as number | '',
    admin_fee_vnd: '' as number | '',
    active: true,
  }));
  const set = (k: keyof typeof form) => (v: any) => setForm((f) => ({ ...f, [k]: v }));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      // PUT upserts by product_id (POST and PUT both upsert per the contract)
      await run('/admin/products', { method: initial ? 'PUT' : 'POST', body: form });
      onDone();
    } catch (e2) { toast.push((e2 as ApiError).message, 'err'); }
  };

  return (
    <form className="card stack" onSubmit={submit}>
      <ErrorBanner error={error} />
      <div className="form-grid">
        <TextField label="Mã sản phẩm" value={form.product_id} onChange={set('product_id')} required hint="vd: HEALTH_BASIC" />
        <SelectField label="Dòng" value={form.category} onChange={set('category')} options={LINES} labelFn={(l) => LINE_LABEL[l as Line]} required />
        <TextField label="Tên sản phẩm" value={form.product_name} onChange={set('product_name')} required />
        <NumberField label="Số tiền bảo hiểm (₫)" value={form.coverage_amount_vnd} onChange={set('coverage_amount_vnd')} min={0} required />
        <NumberField label="Mức miễn thường (₫)" value={form.deductible_vnd} onChange={set('deductible_vnd')} min={0} required />
        <NumberField label="Phí tham chiếu (₫)" value={form.base_premium_vnd} onChange={set('base_premium_vnd')} min={0} required />
        <NumberField label="Phí quản lý (₫)" value={form.admin_fee_vnd} onChange={set('admin_fee_vnd')} min={0} required />
      </div>
      <Toggle label="Đang hoạt động" value={form.active} onChange={set('active')} />
      <div className="row">
        <button className="btn btn-primary" disabled={busy}>{busy ? <Spinner /> : 'Lưu'}</button>
        <button type="button" className="btn btn-ghost" onClick={onCancel}>Hủy</button>
      </div>
    </form>
  );
}

/* ---------- rate versions + loading factors ---------- */
function RatesTab() {
  const toast = useToast();
  const { data, error, loading, reload } = useApi<RateVersion[]>('/admin/rate-versions');
  const { run, busy } = useMutation();
  const [line, setLine] = useState<Line>('health');
  const [value, setValue] = useState<number | ''>('');

  const setFactor = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await run('/admin/loading-factors', { method: 'PUT', body: { line, loading_value: value === '' ? 0 : value } });
      toast.push(`Đã đặt hệ số tải ${LINE_LABEL[line]} = ${value}. Phiên bản rate mới đã tạo.`);
      setValue('');
      reload();
    } catch (e2) { toast.push((e2 as ApiError).message, 'err'); }
  };

  return (
    <div className="stack">
      <form className="card stack" onSubmit={setFactor}>
        <h3 style={{ fontSize: 'var(--step-1)' }}>Đặt hệ số tải theo dòng</h3>
        <p className="muted" style={{ marginTop: -8 }}>Mỗi lần đặt sẽ tạo một phiên bản rate mới và áp dụng vào báo giá (trong khoảng cache ~5 phút).</p>
        <div className="form-grid">
          <SelectField label="Dòng" value={line} onChange={(v) => setLine(v as Line)} options={LINES} labelFn={(l) => LINE_LABEL[l as Line]} />
          <NumberField label="Hệ số tải" value={value} onChange={setValue} hint="vd: 1.2" required />
        </div>
        <button className="btn btn-primary" disabled={busy || value === ''}>{busy ? <Spinner /> : 'Áp dụng hệ số'}</button>
      </form>

      <div className="card stack">
        <h3 style={{ fontSize: 'var(--step-1)' }}>Lịch sử phiên bản rate</h3>
        {loading && <Loading />}
        <ErrorBanner error={error} />
        {data && data.length === 0 && <EmptyState title="Chưa có phiên bản rate" />}
        {data && data.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Mã phiên bản</th><th>Hiệu lực</th><th>Tạo bởi</th><th>Hiện hành</th></tr></thead>
              <tbody>
                {data.map((r) => (
                  <tr key={r.rate_version_id}>
                    <td className="mono" style={{ fontSize: '0.78rem' }}>{r.rate_version_id.slice(0, 12)}</td>
                    <td className="muted">{dateTime(r.effective_at)}</td>
                    <td className="mono" style={{ fontSize: '0.8rem' }}>{r.created_by}</td>
                    <td>{r.is_current ? <span className="pill pill-ok">hiện hành</span> : <span className="pill pill-muted">cũ</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
