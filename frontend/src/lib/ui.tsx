import { createContext, useContext, useState, useCallback, ReactNode } from 'react';
import { ApiError } from '../api/client';
import { viStatus } from './labels';

/* ---------- Spinner ---------- */
export function Spinner({ lg }: { lg?: boolean }) {
  return <span className={lg ? 'spinner spinner-lg' : 'spinner'} aria-label="Đang tải" role="status" />;
}

export function Loading({ label = 'Đang tải…' }: { label?: string }) {
  return (
    <div className="row center" style={{ justifyContent: 'center', padding: 'var(--s7)', color: 'var(--ink-faint)' }}>
      <Spinner lg /> <span style={{ marginLeft: 12 }}>{label}</span>
    </div>
  );
}

/* ---------- Alert ---------- */
type AlertKind = 'err' | 'ok' | 'warn' | 'info';
export function Alert({ kind = 'info', children }: { kind?: AlertKind; children: ReactNode }) {
  return <div className={`alert alert-${kind}`}>{children}</div>;
}

/** Render an ApiError as a friendly alert, surfacing field details + correlation id. */
export function ErrorBanner({ error }: { error: ApiError | null }) {
  if (!error) return null;
  return (
    <div className="alert alert-err">
      <div style={{ fontWeight: 600 }}>{error.message}</div>
      {error.details && typeof error.details === 'object' && (
        <ul style={{ margin: '6px 0 0', paddingLeft: 18, fontSize: '0.85rem' }}>
          {Object.entries(error.details).map(([k, v]) => (
            <li key={k}>
              <span className="mono">{k}</span>: {String(v)}
            </li>
          ))}
        </ul>
      )}
      {error.correlationId && (
        <div className="mono" style={{ fontSize: '0.72rem', marginTop: 8, opacity: 0.7 }}>
          ref: {error.correlationId}
        </div>
      )}
    </div>
  );
}

/* ---------- EmptyState ---------- */
export function EmptyState({ mark = '○', title, hint }: { mark?: string; title: string; hint?: string }) {
  return (
    <div className="empty">
      <div className="empty-mark">{mark}</div>
      <div style={{ fontWeight: 600, color: 'var(--ink-soft)' }}>{title}</div>
      {hint && <div style={{ marginTop: 6, fontSize: '0.88rem' }}>{hint}</div>}
    </div>
  );
}

/* ---------- Pill ---------- */
const PILL_MAP: Record<string, string> = {
  // generic states
  active: 'pill-ok', paid: 'pill-ok', completed: 'pill-ok', success: 'pill-ok',
  approve: 'pill-ok', APPROVE: 'pill-ok', sent: 'pill-ok',
  pending: 'pill-wait', pending_payment: 'pill-wait', PENDING_PAYMENT: 'pill-wait',
  PENDING_REVIEW: 'pill-wait', unpaid: 'pill-wait', pending_review: 'pill-wait',
  rejected: 'pill-bad', REJECTED: 'pill-bad', failed: 'pill-bad', cancelled: 'pill-bad',
  CANCELLED: 'pill-bad', voided: 'pill-bad', expired: 'pill-bad', reject: 'pill-bad',
};
export function StatusPill({ status }: { status: string | null | undefined }) {
  if (!status) return <span className="pill pill-muted">—</span>;
  const cls = PILL_MAP[status] || PILL_MAP[status.toLowerCase()] || 'pill-muted';
  return <span className={`pill ${cls}`}>{viStatus(status)}</span>;
}

/* ---------- Toasts ---------- */
interface Toast {
  id: number;
  msg: string;
  kind: 'ok' | 'err' | 'warn';
}
interface ToastCtx {
  push: (msg: string, kind?: Toast['kind']) => void;
}
const TCtx = createContext<ToastCtx>({ push: () => {} });
export const useToast = () => useContext(TCtx);

let toastSeq = 1;
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((msg: string, kind: Toast['kind'] = 'ok') => {
    const id = toastSeq++;
    setToasts((t) => [...t, { id, msg, kind }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4200);
  }, []);
  return (
    <TCtx.Provider value={{ push }}>
      {children}
      <div className="toast-wrap" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.kind === 'ok' ? '' : t.kind}`}>
            {t.msg}
          </div>
        ))}
      </div>
    </TCtx.Provider>
  );
}
