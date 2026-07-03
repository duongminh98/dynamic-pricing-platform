import { Fragment, useState } from 'react';
import { ApiError } from '../../api/client';
import { useApi, useMutation, dateOnly, dateTime, vndLabel } from '../../lib/format';
import { viEnum } from '../../lib/labels';
import { usePaged, Pager } from '../../lib/paged';
import { LINE_LABEL, PROVINCES, Line } from '../../lib/domain';
import { Loading, ErrorBanner, EmptyState, Spinner, useToast } from '../../lib/ui';

interface Customer {
  customer_id: string; email: string; province: string; occupation: string;
  age: number; failed_login_count: number; locked_until: string | null; created_at: string;
  account_id?: string; keycloak_subject?: string; full_name?: string; fullName?: string; gender?: string; region?: string; urban_tier?: string;
  income_level?: string; monthly_income_vnd?: number | null; marital_status?: string; updated_at?: string | null;
  line_profiles?: LineProfile[]; lineProfiles?: LineProfile[];
}
interface LineProfile { version_id?: string; versionId?: string; line: string; line_attributes?: Record<string, unknown>; lineAttributes?: Record<string, unknown>; effective_at?: string; effectiveAt?: string; }

export default function AdminCustomers() {
  const toast = useToast();
  const [q, setQ] = useState('');
  const [qInput, setQInput] = useState('');
  const [province, setProvince] = useState('');
  const [locked, setLocked] = useState('');
  const [viewing, setViewing] = useState<string | null>(null);
  const [acting, setActing] = useState<string | null>(null);
  const { run, busy } = useMutation();
  const { data, error, loading, page, setPage, reload } = usePaged<Customer>('/admin/customers', { q, province, locked });

  const isLocked = (c: Customer) => c.locked_until && new Date(c.locked_until).getTime() > Date.now();

  const lock = async (id: string) => {
    try { await run(`/admin/customers/${id}/lock`, { method: 'POST', body: { hours: 24 } }); toast.push('Đã khóa tài khoản 24 giờ.'); reload(); }
    catch (e) { toast.push((e as ApiError).message, 'err'); } finally { setActing(null); }
  };
  const unlock = async (id: string) => {
    try { await run(`/admin/customers/${id}/unlock`, { method: 'POST', body: {} }); toast.push('Đã mở khóa tài khoản.'); reload(); }
    catch (e) { toast.push((e as ApiError).message, 'err'); } finally { setActing(null); }
  };

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Khách hàng</p>
        <h2>Quản lý khách hàng</h2>
      </div>

      <div className="toolbar">
        <form className="field" style={{ margin: 0 }} onSubmit={(e) => { e.preventDefault(); setQ(qInput); setPage(0); }}>
          <span className="label">Tìm email</span>
          <input className="input" value={qInput} onChange={(e) => setQInput(e.target.value)} placeholder="email…" style={{ minWidth: 220 }} />
        </form>
        <label className="field" style={{ margin: 0 }}>
          <span className="label">Tỉnh / Thành</span>
          <select className="select" value={province} onChange={(e) => { setProvince(e.target.value); setPage(0); }}>
            <option value="">Tất cả</option>
            {PROVINCES.map((p) => <option key={p} value={p}>{p}</option>)}
          </select>
        </label>
        <label className="field" style={{ margin: 0 }}>
          <span className="label">Trạng thái khóa</span>
          <select className="select" value={locked} onChange={(e) => { setLocked(e.target.value); setPage(0); }}>
            <option value="">Tất cả</option>
            <option value="true">Đang khóa</option>
            <option value="false">Không khóa</option>
          </select>
        </label>
      </div>

      {loading && <Loading />}
      <ErrorBanner error={error} />
      {data && data.content.length === 0 && <EmptyState title="Không có khách hàng" />}

      {data && data.content.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Khách hàng</th><th>Email</th><th>Tỉnh</th><th>Nghề</th><th>Tuổi</th><th>Trạng thái</th><th></th></tr></thead>
            <tbody>
              {data.content.map((c) => (
                <Fragment key={c.customer_id}>
                  <tr
                    role="button"
                    tabIndex={0}
                    style={{ cursor: 'pointer' }}
                    onClick={() => setViewing(viewing === c.customer_id ? null : c.customer_id)}
                    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setViewing(viewing === c.customer_id ? null : c.customer_id); }}
                  >
                    <td>{displayName(c)}</td>
                    <td>{c.email}</td>
                    <td className="muted">{c.province}</td>
                    <td className="muted">{viEnum(c.occupation)}</td>
                    <td className="num">{c.age}</td>
                    <td>{isLocked(c) ? <span className="pill pill-bad">Locked</span> : <span className="pill pill-ok">Active</span>}</td>
                    <td className="num">
                      {isLocked(c) ? (
                        <button className="btn btn-ghost btn-sm" disabled={busy && acting === c.customer_id} onClick={(e) => { e.stopPropagation(); setActing(c.customer_id); unlock(c.customer_id); }}>
                          {busy && acting === c.customer_id ? <Spinner /> : 'Unlock'}
                        </button>
                      ) : (
                        <button className="btn btn-danger btn-sm" disabled={busy && acting === c.customer_id} onClick={(e) => { e.stopPropagation(); setActing(c.customer_id); lock(c.customer_id); }}>
                          {busy && acting === c.customer_id ? <Spinner /> : 'Lock'}
                        </button>
                      )}
                    </td>
                  </tr>
                  {viewing === c.customer_id && (
                    <tr key={c.customer_id + '-d'}>
                      <td colSpan={7} style={{ background: 'var(--surface-2)' }}>
                        <CustomerDetailPanel customerId={c.customer_id} onClose={() => setViewing(null)} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && <Pager page={page} totalPages={data.total_pages} total={data.total_elements} onPage={setPage} />}
    </div>
  );
}

function CustomerDetailPanel({ customerId, onClose }: { customerId: string; onClose: () => void }) {
  const { data: customer, error, loading } = useApi<Customer>(`/admin/customers/${customerId}`, [customerId]);
  const locked = customer?.locked_until && new Date(customer.locked_until).getTime() > Date.now();

  return (
    <div className="stack" style={{ padding: 'var(--s3) 0' }}>
      {loading && <Loading />}
      <ErrorBanner error={error} />
      {customer && (
        <div className="card stack" style={{ background: 'var(--raised)' }}>
          <div className="row-between">
            <div>
              <span className="mono faint" style={{ fontSize: '0.78rem' }}>{customer.customer_id}</span>
              <h3 style={{ marginTop: 4 }}>{displayName(customer)}</h3>
              <div className="muted">{customer.email}</div>
            </div>
            {locked ? <span className="pill pill-bad">Locked</span> : <span className="pill pill-ok">Active</span>}
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Account</h4>
            <Kv label="Account ID" value={customer.account_id || '-'} first />
            <Kv label="Locked Until" value={dateTime(customer.locked_until)} />
            <Kv label="Created" value={dateTime(customer.created_at)} />
            <Kv label="Updated" value={dateTime(customer.updated_at)} />
          </div>

          <div className="panel">
            <h4 style={{ marginTop: 0 }}>Profile</h4>
            <Kv label="Age" value={String(customer.age ?? '-')} first />
            <Kv label="Gender" value={viEnum(customer.gender || '-')} />
            <Kv label="Province" value={customer.province || '-'} />
            <Kv label="Region" value={viEnum(customer.region || '-')} />
            <Kv label="Urban Tier" value={viEnum(customer.urban_tier || '-')} />
            <Kv label="Occupation" value={viEnum(customer.occupation || '-')} />
            <Kv label="Income Level" value={viEnum(customer.income_level || '-')} />
            <Kv label="Monthly Income" value={customer.monthly_income_vnd == null ? '-' : vndLabel(customer.monthly_income_vnd)} />
            <Kv label="Marital Status" value={viEnum(customer.marital_status || '-')} />
          </div>

          <LineProfilesPanel profiles={customer.line_profiles ?? customer.lineProfiles ?? []} />

          <button className="btn btn-ghost" onClick={onClose}>Close</button>
        </div>
      )}
    </div>
  );
}

function Kv({ label, value, first = false }: { label: string; value: string; first?: boolean }) {
  return <div className="kv" style={first ? { borderTop: 'none' } : undefined}><span className="kv-k">{label}</span><span className="kv-v">{value}</span></div>;
}

function displayName(customer: Customer): string {
  // Prefer the real name synced from Keycloak; fall back to a derived gender·age label,
  // then to the email, so the column is never blank.
  const keycloakName = (customer.full_name ?? customer.fullName)?.trim();
  if (keycloakName) return keycloakName;
  const derived = [customer.gender ? viEnum(customer.gender) : null, customer.age ? String(customer.age) : null].filter(Boolean).join(' · ');
  return derived || customer.email;
}

function LineProfilesPanel({ profiles }: { profiles: LineProfile[] }) {
  return (
    <div className="panel stack">
      <h4 style={{ marginTop: 0 }}>Product-specific profiles</h4>
      {profiles.length === 0 && <p className="muted">No product-specific profiles.</p>}
      {profiles.map((profile) => {
        const attrs = profile.line_attributes ?? profile.lineAttributes ?? {};
        const effectiveAt = profile.effective_at ?? profile.effectiveAt;
        return (
          <div key={(profile.version_id ?? profile.versionId ?? profile.line)} className="panel">
            <div className="row-between">
              <strong>{LINE_LABEL[profile.line as Line] || profile.line}</strong>
              <span className="muted">{dateTime(effectiveAt)}</span>
            </div>
            {Object.entries(attrs).map(([key, value], idx) => (
              <Kv key={key} label={key} value={String(value ?? '-')} first={idx === 0} />
            ))}
          </div>
        );
      })}
    </div>
  );
}
