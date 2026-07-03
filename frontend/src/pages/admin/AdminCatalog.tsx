import { useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import { useApi, useMutation, vndLabel, dateTime } from '../../lib/format';
import { LINES, LINE_LABEL, Line, PROVINCES } from '../../lib/domain';
import { Loading, ErrorBanner, EmptyState, Spinner, useToast } from '../../lib/ui';
import { TextField, NumberField, SelectField, Toggle } from '../../lib/fields';

interface ProductSummary {
  product_id: string; line: string; product_name: string;
  coverage_amount_vnd: number; deductible_vnd: number;
  base_premium_vnd: number; admin_fee_vnd: number;
}
interface RateVersion {
  rate_version_id: string; effective_at: string; created_by: string; is_current: boolean; created_at: string;
}
interface ReferenceVersion<T> {
  version_id: string; reference_type: string; status: string; checksum: string; change_reason: string; activated_at: string; created_by?: string; rows: T[];
}
interface GeoRiskRow { province: string; traffic_density_score?: number; flood_risk_score?: number; hospital_cost_index?: number; repair_cost_index?: number; construction_cost_index?: number; [key: string]: any; }
interface CostIndexRow { month_start: string; medical_inflation_index?: number; vehicle_repair_inflation_index?: number; construction_inflation_index?: number; travel_medical_cost_index?: number; general_expense_index?: number; [key: string]: any; }

export default function AdminCatalog() {
  const [tab, setTab] = useState<'products' | 'rates' | 'reference'>('products');
  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Cấu hình định giá</p>
        <h2>Danh mục & hệ số giá</h2>
      </div>
      <div className="tabs">
        <button className={'tab' + (tab === 'products' ? ' active' : '')} onClick={() => setTab('products')}>Sản phẩm</button>
        <button className={'tab' + (tab === 'rates' ? ' active' : '')} onClick={() => setTab('rates')}>Hệ số tải & phiên bản</button>
        <button className={'tab' + (tab === 'reference' ? ' active' : '')} onClick={() => setTab('reference')}>Reference data</button>
      </div>
      {tab === 'products' && <ProductsTab />}
      {tab === 'rates' && <RatesTab />}
      {tab === 'reference' && <ReferenceDataTab />}
    </div>
  );
}

/* ---------- products ---------- */
function ProductsTab() {
  const toast = useToast();
  const { data, error, loading, reload } = useApi<ProductSummary[]>('/products');
  const [editing, setEditing] = useState<ProductSummary | null>(null);

  return (
    <div className="stack">
      <div className="row-between">
        <span className="muted">{data?.length ?? 0} sản phẩm đang hoạt động</span>
      </div>

      {editing && <ProductForm initial={editing} onDone={() => { setEditing(null); reload(); toast.push('Đã lưu sản phẩm.'); }} onCancel={() => setEditing(null)} />}

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

function ProductForm({ initial, onDone, onCancel }: { initial: ProductSummary; onDone: () => void; onCancel: () => void }) {
  const toast = useToast();
  const { run, busy, error } = useMutation();
  const [form, setForm] = useState(() => ({
    product_id: initial.product_id,
    category: initial.line as string,
    product_name: initial.product_name,
    coverage_amount_vnd: (initial.coverage_amount_vnd ?? '') as number | '',
    deductible_vnd: (initial.deductible_vnd ?? '') as number | '',
    base_premium_vnd: (initial.base_premium_vnd ?? '') as number | '',
    admin_fee_vnd: (initial.admin_fee_vnd ?? '') as number | '',
    active: true,
  }));
  const set = (k: keyof typeof form) => (v: any) => setForm((f) => ({ ...f, [k]: v }));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await run('/admin/products', { method: 'PUT', body: form });
      onDone();
    } catch (e2) { toast.push((e2 as ApiError).message, 'err'); }
  };

  return (
    <form className="card stack" onSubmit={submit}>
      <ErrorBanner error={error} />
      <div className="form-grid">
        <TextField label="Mã sản phẩm" value={form.product_id} onChange={set('product_id')} required hint="vd: HEALTH_BASIC" disabled />
        <SelectField label="Dòng" value={form.category} onChange={set('category')} options={LINES} labelFn={(l) => LINE_LABEL[l as Line]} required disabled />
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


/* ---------- pricing reference data ---------- */
const GEO_NUMERIC_FIELDS = [
  'traffic_density_score', 'vehicle_theft_risk_score', 'accident_frequency_index',
  'flood_risk_score', 'storm_risk_score', 'fire_risk_score', 'crime_risk_score',
  'healthcare_access_score', 'hospital_cost_index', 'repair_cost_index', 'construction_cost_index',
] as const;
const COST_NUMERIC_FIELDS = [
  'medical_inflation_index', 'vehicle_repair_inflation_index', 'construction_inflation_index',
  'travel_medical_cost_index', 'general_expense_index',
] as const;

function ReferenceDataTab() {
  return (
    <div className="stack">
      <GeoRiskEditor />
      <CostIndexEditor />
    </div>
  );
}

function GeoRiskEditor() {
  const toast = useToast();
  const { data, error, loading, reload } = useApi<ReferenceVersion<GeoRiskRow>>('/admin/pricing-reference/geo-risk');
  const { run, busy, error: saveError } = useMutation();
  const [province, setProvince] = useState<string>('Ha Noi');
  const [reason, setReason] = useState('Update geo risk reference data');
  const [draft, setDraft] = useState<GeoRiskRow | null>(null);
  const [search, setSearch] = useState('');
  const versions = useApi<ReferenceVersion<GeoRiskRow>[]>('/admin/pricing-reference/geo-risk/versions');

  useEffect(() => {
    const row = data?.rows?.find((r) => r.province === province) ?? data?.rows?.[0] ?? null;
    if (row) {
      setProvince(row.province);
      setDraft({ ...row });
    }
  }, [data]);

  const selectProvince = (value: string) => {
    setProvince(value);
    const row = data?.rows?.find((r) => r.province === value);
    setDraft(row ? { ...row } : { province: value, region: '', urban_tier_geo: 'tier1' });
  };
  const setText = (key: keyof GeoRiskRow) => (value: string) => setDraft((row) => ({ ...(row ?? { province }), [key]: value }));
  const setNum = (key: string) => (value: number | '') => setDraft((row) => ({ ...(row ?? { province }), [key]: value === '' ? 0 : value }));
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!draft) return;
    const rows = replaceRow(data?.rows ?? [], 'province', draft.province, draft);
    try {
      await run('/admin/pricing-reference/geo-risk', { method: 'PUT', body: { change_reason: reason, rows } });
      reload();
      toast.push('Geo risk version activated.');
    } catch (e2) { toast.push((e2 as ApiError).message, 'err'); }
  };

  return (
    <div className="card stack">
      <ReferenceHeader title="Geo risk" data={data} />
      {loading && <Loading />}
      <ErrorBanner error={error || saveError} />
      <form className="stack" onSubmit={submit}>
        <div className="form-grid">
          <SelectField label="Province" value={province} onChange={selectProvince} options={PROVINCES} />
          <TextField label="Region" value={draft?.region ?? ''} onChange={setText('region')} />
          <SelectField label="Urban tier" value={draft?.urban_tier_geo ?? 'tier1'} onChange={setText('urban_tier_geo')} options={['tier1', 'urban', 'rural']} />
          <TextField label="Change reason" value={reason} onChange={setReason} required />
          {GEO_NUMERIC_FIELDS.map((field) => (
            <NumberField key={field} label={field} value={(draft?.[field] ?? 0) as number} onChange={setNum(field)} min={0} required />
          ))}
        </div>
        <div className="row">
          <button className="btn btn-primary" disabled={busy || !draft}>{busy ? <Spinner /> : 'Activate geo risk version'}</button>
          <button type="button" className="btn btn-ghost" onClick={() => selectProvince(province || 'New Province')}>Add new province</button>
        </div>
      </form>
      <PreviewTable rows={data?.rows ?? []} columns={['province', 'region', 'urban_tier_geo', ...GEO_NUMERIC_FIELDS]} />
      <VersionHistory title="Geo risk history" data={versions.data} loading={versions.loading} error={versions.error} />
    </div>
  );
}

function CostIndexEditor() {
  const toast = useToast();
  const { data, error, loading, reload } = useApi<ReferenceVersion<CostIndexRow>>('/admin/pricing-reference/cost-indices');
  const { run, busy, error: saveError } = useMutation();
  const [monthStart, setMonthStart] = useState('');
  const [reason, setReason] = useState('Update cost index reference data');
  const [draft, setDraft] = useState<CostIndexRow | null>(null);
  const [search, setSearch] = useState('');
  const versions = useApi<ReferenceVersion<CostIndexRow>[]>('/admin/pricing-reference/cost-indices/versions');
  const monthOptions = filterOptions(data?.rows?.map((r) => r.month_start).sort().reverse() ?? [], search);

  useEffect(() => {
    const row = data?.rows?.slice().sort((a, b) => b.month_start.localeCompare(a.month_start))[0] ?? null;
    if (row) {
      setMonthStart(row.month_start);
      setDraft({ ...row });
    }
  }, [data]);

  const selectMonth = (value: string) => {
    setMonthStart(value);
    const row = data?.rows?.find((r) => r.month_start === value);
    setDraft(row ? { ...row } : newCostRow(value));
  };
  const setMonth = (value: string) => {
    setMonthStart(value);
    setDraft((row) => ({ ...(row ?? newCostRow(value)), ...monthParts(value), month_start: value }));
  };
  const setNum = (key: string) => (value: number | '') => setDraft((row) => ({ ...(row ?? newCostRow(monthStart)), [key]: value === '' ? 0 : value }));
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!draft) return;
    const rows = replaceRow(data?.rows ?? [], 'month_start', draft.month_start, draft);
    try {
      await run('/admin/pricing-reference/cost-indices', { method: 'PUT', body: { change_reason: reason, rows } });
      reload();
      toast.push('Cost index version activated.');
    } catch (e2) { toast.push((e2 as ApiError).message, 'err'); }
  };

  return (
    <div className="card stack">
      <ReferenceHeader title="Cost indices" data={data} />
      {loading && <Loading />}
      <ErrorBanner error={error || saveError} />
      <form className="stack" onSubmit={submit}>
        <div className="form-grid">
          <TextField label="Search month" value={search} onChange={setSearch} placeholder="YYYY-MM" />
          <SelectField label="Existing month" value={monthStart} onChange={selectMonth} options={monthOptions} placeholder="Select month" />
          <TextField label="Month start" value={draft?.month_start ?? ''} onChange={setMonth} placeholder="2026-07-01" required />
          <TextField label="Change reason" value={reason} onChange={setReason} required />
          {COST_NUMERIC_FIELDS.map((field) => (
            <NumberField key={field} label={field} value={(draft?.[field] ?? 1) as number} onChange={setNum(field)} min={0} required />
          ))}
        </div>
        <div className="row">
          <button className="btn btn-primary" disabled={busy || !draft}>{busy ? <Spinner /> : 'Activate cost index version'}</button>
          <button type="button" className="btn btn-ghost" onClick={() => setMonth(nextMonthStart(data?.rows ?? []))}>Add next month</button>
        </div>
      </form>
      <PreviewTable rows={filterRows(data?.rows ?? [], 'month_start', search)} columns={['month_start', 'year', 'month', ...COST_NUMERIC_FIELDS]} />
      <VersionHistory title="Cost index history" data={versions.data} loading={versions.loading} error={versions.error} />
    </div>
  );
}

function ReferenceHeader<T>({ title, data }: { title: string; data: ReferenceVersion<T> | null }) {
  return (
    <div className="row-between">
      <div>
        <h3 style={{ fontSize: 'var(--step-1)' }}>{title}</h3>
        <p className="muted" style={{ marginTop: 4 }}>Active version: <span className="mono">{data?.version_id?.slice(0, 12) ?? 'none'}</span></p>
      </div>
      <span className="pill pill-muted">{data?.rows?.length ?? 0} rows</span>
    </div>
  );
}

function PreviewTable({ rows, columns }: { rows: Record<string, any>[]; columns: string[] }) {
  if (!rows.length) return <EmptyState title="No active reference data" />;
  return (
    <div className="table-wrap" style={{ maxHeight: 360, overflow: 'auto' }}>
      <table className="table" style={{ minWidth: 1100 }}>
        <thead><tr>{columns.map((c) => <th key={c}>{c}</th>)}</tr></thead>
        <tbody>
          {rows.map((row, idx) => (
            <tr key={idx}>{columns.map((c) => <td key={c} className={typeof row[c] === 'number' ? 'num' : undefined}>{String(row[c] ?? '')}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}


function VersionHistory<T>({ title, data, loading, error }: { title: string; data: ReferenceVersion<T>[] | null; loading: boolean; error: ApiError | null }) {
  return (
    <div className="panel stack">
      <h4 style={{ margin: 0 }}>{title}</h4>
      {loading && <Loading />}
      <ErrorBanner error={error} />
      {data && data.length > 0 ? (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Version</th><th>Status</th><th>Rows</th><th>Reason</th><th>Activated</th></tr></thead>
            <tbody>
              {data.slice(0, 8).map((v) => (
                <tr key={v.version_id}>
                  <td className="mono">{v.version_id.slice(0, 12)}</td>
                  <td><span className={'pill ' + (v.status === 'ACTIVE' ? 'pill-ok' : 'pill-muted')}>{v.status}</span></td>
                  <td className="num">{v.rows?.length ?? 0}</td>
                  <td>{v.change_reason}</td>
                  <td className="muted">{dateTime(v.activated_at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !loading && <EmptyState title="No version history" />}
    </div>
  );
}

function replaceRow<T extends Record<string, any>>(rows: T[], key: string, value: string, next: T): T[] {
  const found = rows.some((row) => row[key] === value);
  return found ? rows.map((row) => row[key] === value ? next : row) : [...rows, next];
}


function filterOptions(options: readonly string[], search: string): string[] {
  const q = search.trim().toLowerCase();
  return q ? options.filter((o) => o.toLowerCase().includes(q)) : [...options];
}

function filterRows<T extends Record<string, any>>(rows: T[], key: string, search: string): T[] {
  const q = search.trim().toLowerCase();
  return q ? rows.filter((row) => String(row[key] ?? '').toLowerCase().includes(q)) : rows;
}

function nextMonthStart(rows: CostIndexRow[]): string {
  const sortedMonths = rows.map((row) => row.month_start).sort();
  const latest = sortedMonths.length ? sortedMonths[sortedMonths.length - 1] : undefined;
  const base = latest ? new Date(latest + 'T00:00:00Z') : new Date();
  base.setUTCMonth(base.getUTCMonth() + 1, 1);
  return base.toISOString().slice(0, 10);
}

function monthParts(monthStart: string): Pick<CostIndexRow, 'year' | 'month'> {
  const [year, month] = monthStart.split('-').map((x) => Number(x));
  return { year: Number.isFinite(year) ? year : 0, month: Number.isFinite(month) ? month : 0 };
}

function newCostRow(monthStart: string): CostIndexRow {
  return { month_start: monthStart, ...monthParts(monthStart), medical_inflation_index: 1, vehicle_repair_inflation_index: 1, construction_inflation_index: 1, travel_medical_cost_index: 1, general_expense_index: 1 };
}

