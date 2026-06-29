import { useEffect, useState } from 'react';
import { apiFetch, ApiError } from '../api/client';
import { useApi, useMutation } from '../lib/format';
import { viEnum } from '../lib/labels';
import { NumberField, SelectField, TextField, Toggle } from '../lib/fields';
import {
  GENDERS, MARITAL, OCCUPATIONS, PROVINCES, LINES, LINE_LABEL, LINE_ICON,
  LINE_FIELDS, Line, AttrField,
} from '../lib/domain';
import { Loading, ErrorBanner, Spinner, useToast } from '../lib/ui';

interface BaseProfile {
  age: number; gender: string; province: string; occupation: string;
  monthly_income_vnd: number; marital_status: string;
  region?: string; urban_tier?: string; income_level?: string;
  lines?: LineProfile[];
}
interface LineProfile {
  version_id: string; line: string; line_attributes: Record<string, any>; effective_at: string;
}

const emptyBase = { age: '' as number | '', gender: '', province: '', occupation: '', monthly_income_vnd: '' as number | '', marital_status: '' };

export default function Profile() {
  const toast = useToast();
  const [tab, setTab] = useState<'base' | Line>('base');
  const [profile, setProfile] = useState<BaseProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [hasProfile, setHasProfile] = useState(false);

  const load = () => {
    setLoading(true);
    apiFetch<BaseProfile>('/customers/me/profile')
      .then((p) => { setProfile(p); setHasProfile(true); })
      .catch((e: ApiError) => { if (e.code === 'RESOURCE_NOT_FOUND') setHasProfile(false); })
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  if (loading) return <Loading />;

  return (
    <div className="stack" style={{ maxWidth: 760 }}>
      <div>
        <p className="eyebrow">Hồ sơ rủi ro</p>
        <h2>{hasProfile ? 'Hồ sơ của bạn' : 'Hoàn thiện hồ sơ'}</h2>
        <p className="muted" style={{ marginTop: 8 }}>
          {hasProfile
            ? 'Hồ sơ này được dùng để định giá. Cập nhật thuộc tính từng dòng để báo giá chính xác hơn.'
            : 'Khai báo thông tin cơ bản trước. Sau đó bạn có thể thêm thuộc tính cho từng dòng bảo hiểm.'}
        </p>
      </div>

      <div className="tabs">
        <button className={'tab' + (tab === 'base' ? ' active' : '')} onClick={() => setTab('base')}>Thông tin cơ bản</button>
        {hasProfile && LINES.map((l) => (
          <button key={l} className={'tab' + (tab === l ? ' active' : '')} onClick={() => setTab(l)}>
            {LINE_ICON[l]} {LINE_LABEL[l]}
          </button>
        ))}
      </div>

      {tab === 'base' ? (
        <BaseForm profile={profile} onSaved={(p) => { setProfile(p); setHasProfile(true); toast.push('Đã lưu hồ sơ cơ bản.'); }} />
      ) : (
        <LineForm
          line={tab}
          existing={profile?.lines?.find((l) => l.line === tab)?.line_attributes}
          onSaved={() => { toast.push(`Đã lưu hồ sơ ${LINE_LABEL[tab].toLowerCase()}.`); load(); }}
        />
      )}
    </div>
  );
}

/* ---------- base profile form ---------- */
function BaseForm({ profile, onSaved }: { profile: BaseProfile | null; onSaved: (p: BaseProfile) => void }) {
  const { run, busy, error } = useMutation();
  const [form, setForm] = useState(() =>
    profile
      ? { age: profile.age, gender: profile.gender, province: profile.province, occupation: profile.occupation, monthly_income_vnd: profile.monthly_income_vnd, marital_status: profile.marital_status }
      : emptyBase,
  );
  const set = (k: keyof typeof form) => (v: any) => setForm((f) => ({ ...f, [k]: v }));
  const fe = (f: string) => (error?.details && error.details[f] ? String(error.details[f]) : undefined);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const saved = await run<BaseProfile>('/customers/me/profile', { method: 'PUT', body: form });
    onSaved(saved);
  };

  return (
    <form className="card stack" onSubmit={submit}>
      <ErrorBanner error={error} />
      <div className="form-grid">
        <NumberField label="Tuổi" value={form.age} onChange={set('age')} min={18} max={100} required error={fe('age')} />
        <SelectField label="Giới tính" value={form.gender} onChange={set('gender')} options={GENDERS} labelFn={viEnum} placeholder="Chọn…" required error={fe('gender')} />
        <SelectField label="Tỉnh / Thành" value={form.province} onChange={set('province')} options={PROVINCES} placeholder="Chọn…" required error={fe('province')} />
        <SelectField label="Nghề nghiệp" value={form.occupation} onChange={set('occupation')} options={OCCUPATIONS} labelFn={viEnum} placeholder="Chọn…" required error={fe('occupation')} />
        <NumberField label="Thu nhập / tháng (₫)" value={form.monthly_income_vnd} onChange={set('monthly_income_vnd')} min={1} required error={fe('monthly_income_vnd')} />
        <SelectField label="Tình trạng hôn nhân" value={form.marital_status} onChange={set('marital_status')} options={MARITAL} labelFn={viEnum} placeholder="Chọn…" required error={fe('marital_status')} />
      </div>

      {profile && (profile.region || profile.urban_tier || profile.income_level) && (
        <div className="row wrap">
          {profile.region && <span className="derived">Khu vực <b>{profile.region}</b></span>}
          {profile.urban_tier && <span className="derived">Phân tầng <b>{viEnum(profile.urban_tier)}</b></span>}
          {profile.income_level && <span className="derived">Mức thu nhập <b>{viEnum(profile.income_level)}</b></span>}
        </div>
      )}
      <p className="field-hint">Khu vực, phân tầng đô thị và mức thu nhập được hệ thống tự suy ra — bạn không cần nhập.</p>

      <button className="btn btn-primary" disabled={busy}>{busy ? <Spinner /> : 'Lưu hồ sơ'}</button>
    </form>
  );
}

/* ---------- per-line form ---------- */
function LineForm({ line, existing, onSaved }: { line: Line; existing?: Record<string, any>; onSaved: () => void }) {
  const { run, busy, error } = useMutation();
  const fields = LINE_FIELDS[line];
  const visibleFields = line === 'health' ? fields.filter((f) => f.key !== 'bmi') : fields;
  const [attrs, setAttrs] = useState<Record<string, any>>(() => {
    const init: Record<string, any> = {};
    for (const f of fields) {
      if (existing && existing[f.key] !== undefined) init[f.key] = existing[f.key];
      else init[f.key] = f.kind === 'bool' ? false : '';
    }
    return init;
  });
  const set = (k: string) => (v: any) => setAttrs((a) => ({ ...a, [k]: v }));
  const fe = (f: string) => (error?.details && (error.details.field === f || error.details[f]) ? String(error.details.reason || error.details[f] || 'Giá trị không hợp lệ') : undefined);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const lineAttributes = line === 'health' ? { ...attrs, bmi: calculateBmi(attrs.height_cm, attrs.weight_kg) } : attrs;
    const body = { line_attributes: lineAttributes };
    await run(`/customers/me/profile/lines/${line}`, { method: 'PUT', body });
    onSaved();
  };

  return (
    <form className="card stack" onSubmit={submit}>
      <ErrorBanner error={error} />
      <div className="form-grid">
        {visibleFields.map((f) => <FieldFor key={f.key} f={f} value={attrs[f.key]} onChange={set(f.key)} error={fe(f.key)} />)}
      </div>
      {line === 'health' && <p className="field-hint">BMI is calculated automatically from height and weight.</p>}
      <button className="btn btn-primary" disabled={busy}>{busy ? <Spinner /> : `Lưu hồ sơ ${LINE_LABEL[line].toLowerCase()}`}</button>
    </form>
  );
}

function calculateBmi(heightCm: unknown, weightKg: unknown) {
  const height = Number(heightCm);
  const weight = Number(weightKg);
  if (!Number.isFinite(height) || !Number.isFinite(weight) || height <= 0 || weight <= 0) return '';
  const heightM = height / 100;
  return Math.round((weight / (heightM * heightM)) * 10) / 10;
}

function FieldFor({ f, value, onChange, error }: { f: AttrField; value: any; onChange: (v: any) => void; error?: string }) {
  if (f.kind === 'bool') {
    // Toggle spans full grid width for readability
    return <div style={{ gridColumn: '1 / -1' }}><Toggle label={f.label} value={!!value} onChange={onChange} /></div>;
  }
  if (f.kind === 'enum') return <SelectField label={f.label} value={value ?? ''} onChange={onChange} options={f.options!} labelFn={viEnum} placeholder="Chọn…" error={error} />;
  if (f.kind === 'number') return <NumberField label={f.label} value={value === '' ? '' : value} onChange={onChange} min={f.min} max={f.max} error={error} />;
  if (f.kind === 'date') return <TextField label={f.label} type="date" value={value ?? ''} onChange={onChange} error={error} />;
  return <TextField label={f.label} value={value ?? ''} onChange={onChange} error={error} />;
}
