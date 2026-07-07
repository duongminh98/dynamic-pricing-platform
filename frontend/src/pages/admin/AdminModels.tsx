import { useState } from 'react';
import { ApiError } from '../../api/client';
import { useApi, useMutation, dateTime } from '../../lib/format';
import { LINES, LINE_LABEL, Line } from '../../lib/domain';
import { Loading, ErrorBanner, EmptyState, Spinner, useToast } from '../../lib/ui';

interface ModelVersion {
  model_version_id: string; line: string; algorithm: string;
  gini: number; rmse: number; mae: number; deviance: number;
  trained_at: string; dataset_desc: string; monotonic_applied: boolean; is_champion: boolean;
  family?: string; status?: string; dataset_version_id?: string; artifact_checksum?: string;
  training_code_version?: string; quality_gates?: Record<string, any>;
}
interface DriftMetric { value: number; threshold: number; needs_recalibration: boolean; status?: string; bins_evaluated?: number; }
interface Drift { line: string; needs_recalibration: boolean; metrics: Record<string, DriftMetric>; }

export default function AdminModels() {
  const [line, setLine] = useState<Line>('car');
  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Quản trị mô hình</p>
        <h2>Định giá & giám sát mô hình</h2>
      </div>

      <div className="tabs" role="tablist">
        {LINES.map((l) => (
          <button
            key={l}
            role="tab"
            aria-selected={l === line}
            className={`tab${l === line ? ' active' : ''}`}
            onClick={() => setLine(l)}
          >
            {LINE_LABEL[l]}
          </button>
        ))}
      </div>

      <ModelsPanel key={line} line={line} />
      <DriftPanel key={`drift-${line}`} line={line} />
    </div>
  );
}


/* ---------- drift (3 metrics) ---------- */
// The drift endpoint currently returns an array of all lines (ignoring ?line=),
// though the contract describes a single object. Normalize both shapes and pick
// the requested line so a shape change on either side can't crash the page.
function pickDrift(data: Drift | Drift[] | null, line: Line): Drift | null {
  if (!data) return null;
  if (Array.isArray(data)) return data.find((d) => d.line === line) ?? null;
  return data;
}

const METRIC_LABEL: Record<string, string> = {
  feature_psi: 'Feature PSI',
  prediction_psi: 'Prediction PSI',
  calibration: 'Calibration',
};

function DriftPanel({ line }: { line: Line }) {
  const { data, loading } = useApi<Drift | Drift[]>(`/pricing/drift?line=${line}`, [line]);
  const drift = pickDrift(data, line);
  const metrics = drift?.metrics ?? {};
  return (
    <div className="card stack">
      <div className="row-between">
        <h3 style={{ fontSize: 'var(--step-1)' }}>Model Drift · {LINE_LABEL[line]}</h3>
        {drift && (drift.needs_recalibration ? <span className="pill pill-bad">needs recalibration</span> : <span className="pill pill-ok">stable</span>)}
      </div>
      {loading && <Loading />}
      {drift && Object.keys(metrics).length > 0 && (
        <div className="cards-grid">
          {Object.entries(metrics).map(([k, m]) => (
            <div className="panel stack" key={k} style={{ gap: 'var(--s2)' }}>
              <div className="row-between">
                <span className="stat-l">{METRIC_LABEL[k] || k}</span>
                {m.needs_recalibration ? <span className="pill pill-bad">over threshold</span> : <span className="pill pill-ok">within threshold</span>}
              </div>
              <div className="figure" style={{ fontSize: 'var(--step-2)' }}>{m.value?.toFixed?.(3) ?? '—'}</div>
              <div className="faint mono" style={{ fontSize: '0.74rem' }}>
                threshold {m.threshold}
                {m.status && ` · ${m.status}`}
                {m.bins_evaluated !== undefined && ` · ${m.bins_evaluated} bins`}
              </div>
            </div>
          ))}
        </div>
      )}
      {!loading && drift && Object.keys(metrics).length === 0 && (
        <p className="faint">No drift metrics available for this line.</p>
      )}
    </div>
  );
}


/* ---------- model versions + promote/rollback ---------- */
function ModelsPanel({ line }: { line: Line }) {
  const toast = useToast();
  const { data, error, loading, reload } = useApi<ModelVersion[]>(`/pricing/models?line=${line}`, [line]);
  const { run, busy } = useMutation();

  const promote = async (id: string) => {
    try {
      const r = await run<{ promoted: boolean; reason?: string; champion: string }>('/admin/champion/promote', { method: 'POST', body: { line, model_version_id: id } });
      // 200 does not mean promoted — read the `promoted` flag (BR-23)
      if (r.promoted) toast.push(`Đã thăng hạng champion: ${r.champion}`);
      else toast.push(`Không thăng hạng: ${r.reason}`, 'warn');
      reload();
    } catch (e) { toast.push((e as ApiError).message, 'err'); }
  };
  const reject = async (id: string) => {
    try {
      const r = await run<{ rejected: boolean; reason?: string }>('/admin/models/reject', { method: 'POST', body: { line, model_version_id: id } });
      if (r.rejected) toast.push(`Rejected candidate: ${id}`);
      else toast.push(`Reject skipped: ${r.reason}`, 'warn');
      reload();
    } catch (e) { toast.push((e as ApiError).message, 'err'); }
  };
  const rollback = async () => {
    try {
      const r = await run<{ rolled_back: boolean; champion: string }>('/admin/champion/rollback', { method: 'POST', body: { line } });
      if (r.rolled_back) toast.push(`Đã rollback về ${r.champion}`);
      reload();
    } catch (e) {
      const err = e as ApiError;
      if (err.status === 400) toast.push('Không có champion trước đó để rollback.', 'warn');
      else toast.push(err.message, 'err');
    }
  };

  return (
    <div className="card stack">
      <div className="row-between">
        <h3 style={{ fontSize: 'var(--step-1)' }}>Model lifecycle · {LINE_LABEL[line]}</h3>
        <button className="btn btn-ghost btn-sm" disabled={busy} onClick={rollback}>↺ Rollback champion</button>
      </div>
      <p className="muted" style={{ marginTop: -8 }}>Review candidate lineage and offline gates.</p>
      {loading && <Loading />}
      <ErrorBanner error={error} />
      {data && data.length === 0 && <EmptyState title="Chưa có phiên bản mô hình cho dòng này" />}
      {data && data.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Version</th><th>Family</th><th>Status</th><th>Dataset</th><th>Gini</th><th>Gates</th><th>Checksum</th><th></th></tr></thead>
            <tbody>
              {data.map((m) => (
                <tr key={m.model_version_id}>
                  <td className="mono" style={{ fontSize: '0.78rem' }}>{m.model_version_id}<div className="faint" style={{ fontSize: '0.7rem' }}>{dateTime(m.trained_at)}</div></td>
                  <td>{m.algorithm}<div className="faint">{m.family || 'freqsev'}</div></td>
                  <td>{m.is_champion ? <span className="pill pill-ok">champion</span> : <span className="pill pill-muted">{m.status || 'unknown'}</span>}</td>
                  <td className="mono" style={{ fontSize: '0.72rem' }}>{m.dataset_version_id || m.dataset_desc}</td>
                  <td className="num">{m.gini?.toFixed(3)}</td>
                  <td>
                    {m.monotonic_applied ? <span className="pill pill-ok">mono</span> : <span className="pill pill-muted">mono</span>}
                    {m.quality_gates?.smoothness_passed ? <span className="pill pill-ok">smooth</span> : <span className="pill pill-muted">smooth</span>}
                    {m.quality_gates?.comparison_passed ? <span className="pill pill-ok">compare</span> : <span className="pill pill-muted">compare</span>}
                  </td>
                  <td className="mono" style={{ fontSize: '0.72rem' }}>{m.artifact_checksum ? m.artifact_checksum.slice(0, 12) : '—'}</td>
                  <td className="num">
                    {!m.is_champion && <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => promote(m.model_version_id)}>Promote</button>}
                    {!m.is_champion && m.status === 'CANDIDATE' && <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => reject(m.model_version_id)}>Reject</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

