import { ReactNode } from 'react';

interface BaseProps {
  label: string;
  error?: string;
  hint?: string;
  required?: boolean;
}

function FieldShell({ label, error, hint, required, children }: BaseProps & { children: ReactNode }) {
  return (
    <label className="field">
      <span className="label">
        {label}
        {required && <span style={{ color: 'var(--terra)' }}> *</span>}
      </span>
      {children}
      {error && <span className="field-err">{error}</span>}
      {!error && hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

interface TextProps extends BaseProps {
  value: string;
  onChange: (v: string) => void;
  type?: string;
  placeholder?: string;
  autoComplete?: string;
}
export function TextField({ value, onChange, type = 'text', placeholder, autoComplete, ...rest }: TextProps) {
  return (
    <FieldShell {...rest}>
      <input
        className={`input ${rest.error ? 'err' : ''}`}
        type={type}
        value={value}
        placeholder={placeholder}
        autoComplete={autoComplete}
        onChange={(e) => onChange(e.target.value)}
      />
    </FieldShell>
  );
}

interface NumProps extends BaseProps {
  value: number | '';
  onChange: (v: number | '') => void;
  min?: number;
  max?: number;
  placeholder?: string;
}
export function NumberField({ value, onChange, min, max, placeholder, ...rest }: NumProps) {
  return (
    <FieldShell {...rest} hint={rest.hint ?? (min !== undefined && max !== undefined ? `${min} – ${max}` : undefined)}>
      <input
        className={`input ${rest.error ? 'err' : ''}`}
        type="number"
        value={value}
        min={min}
        max={max}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))}
      />
    </FieldShell>
  );
}

interface SelectProps extends BaseProps {
  value: string;
  onChange: (v: string) => void;
  options: readonly string[];
  labelFn?: (o: string) => string;
  placeholder?: string;
}
export function SelectField({ value, onChange, options, labelFn, placeholder, ...rest }: SelectProps) {
  return (
    <FieldShell {...rest}>
      <select className={`select ${rest.error ? 'err' : ''}`} value={value} onChange={(e) => onChange(e.target.value)}>
        {placeholder !== undefined && <option value="">{placeholder}</option>}
        {options.map((o) => (
          <option key={o} value={o}>
            {labelFn ? labelFn(o) : o}
          </option>
        ))}
      </select>
    </FieldShell>
  );
}

interface ToggleProps {
  label: string;
  value: boolean;
  onChange: (v: boolean) => void;
}
export function Toggle({ label, value, onChange }: ToggleProps) {
  return (
    <label
      className="row"
      style={{
        justifyContent: 'space-between',
        padding: '9px 12px',
        border: '1px solid var(--line-strong)',
        borderRadius: 'var(--radius-sm)',
        marginBottom: 'var(--s4)',
        cursor: 'pointer',
        background: value ? 'var(--jade-soft)' : 'var(--raised)',
        borderColor: value ? 'var(--jade)' : 'var(--line-strong)',
      }}
    >
      <span style={{ fontSize: '0.9rem', fontWeight: 500 }}>{label}</span>
      <input type="checkbox" checked={value} onChange={(e) => onChange(e.target.checked)} style={{ accentColor: 'var(--jade)', width: 17, height: 17 }} />
    </label>
  );
}

interface TextAreaProps extends BaseProps {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
}
export function TextAreaField({ value, onChange, placeholder, ...rest }: TextAreaProps) {
  return (
    <FieldShell {...rest}>
      <textarea className={`textarea ${rest.error ? 'err' : ''}`} value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
    </FieldShell>
  );
}
