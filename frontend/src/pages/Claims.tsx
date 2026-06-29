import { useState } from 'react';
import { ApiError } from '../api/client';
import { useApi, useMutation, vndLabel, dateTime } from '../lib/format';
import { viEnum, viProduct } from '../lib/labels';
import { TextField, TextAreaField, NumberField, SelectField } from '../lib/fields';
import { Loading, ErrorBanner, EmptyState, StatusPill, Spinner, Alert, useToast } from '../lib/ui';

interface PolicyResponse { policy_id: string; product_id: string; line: string | null; status: string; }
interface ClaimResponse {
  claim_id: string; policy_id: string; loss_type: string; claim_status: string;
  occurrence_date: string; estimated_cost: number; paid_amount_vnd?: number; admin_note?: string | null;
}
interface Page<T> { content: T[]; total_elements: number; }

const LOSS_TYPES = ['collision', 'theft', 'fire', 'flood', 'medical', 'accident', 'liability', 'other'];

export default function Claims() {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const { data: claims, error, loading, reload } = useApi<Page<ClaimResponse>>('/claims?page=0&size=50');
  const { data: policies } = useApi<PolicyResponse[]>('/policies');
  const activePolicies = (policies || []).filter((p) => p.status === 'active');

  return (
    <div className="stack">
      <div className="row-between">
        <div>
          <p className="eyebrow">Bồi thường</p>
          <h2>Yêu cầu bồi thường</h2>
        </div>
        <button className="btn btn-primary" onClick={() => setOpen((o) => !o)} disabled={activePolicies.length === 0}>
          {open ? 'Đóng' : '+ Khai báo tổn thất'}
        </button>
      </div>

      {activePolicies.length === 0 && <Alert kind="info">Bạn cần có hợp đồng đang hiệu lực để khai báo bồi thường.</Alert>}

      {open && (
        <FnolForm
          policies={activePolicies}
          onDone={() => { setOpen(false); reload(); toast.push('Đã gửi yêu cầu bồi thường.'); }}
        />
      )}

      {loading && <Loading />}
      <ErrorBanner error={error} />
      {claims && claims.content.length === 0 && !open && <EmptyState mark="✚" title="Chưa có yêu cầu bồi thường" />}

      {claims && claims.content.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Mã</th><th>Loại tổn thất</th><th>Ngày xảy ra</th><th>Ước tính</th><th>Đã chi trả</th><th>Trạng thái</th></tr></thead>
            <tbody>
              {claims.content.map((c) => (
                <tr key={c.claim_id}>
                  <td className="mono" style={{ fontSize: '0.78rem' }}>{c.claim_id.slice(0, 8)}</td>
                  <td>{viEnum(c.loss_type)}</td>
                  <td className="muted">{dateTime(c.occurrence_date)}</td>
                  <td className="num">{vndLabel(c.estimated_cost)}</td>
                  <td className="num">{c.paid_amount_vnd ? vndLabel(c.paid_amount_vnd) : '—'}</td>
                  <td><StatusPill status={c.claim_status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function FnolForm({ policies, onDone }: { policies: PolicyResponse[]; onDone: () => void }) {
  const toast = useToast();
  const { run, busy, error } = useMutation();
  const [policyId, setPolicyId] = useState(policies[0]?.policy_id || '');
  const [lossType, setLossType] = useState('');
  const [occurrence, setOccurrence] = useState('');
  const [description, setDescription] = useState('');
  const [cost, setCost] = useState<number | ''>('');

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await run('/claims', {
        method: 'POST',
        body: {
          policy_id: policyId, loss_type: lossType,
          occurrence_date: occurrence ? new Date(occurrence).toISOString() : null,
          description, estimated_cost: cost === '' ? 0 : cost, attachments: [],
        },
      });
      onDone();
    } catch (e2) {
      const err = e2 as ApiError;
      if (err.code === 'OCCURRENCE_OUT_OF_COVERAGE') toast.push('Ngày xảy ra nằm ngoài thời gian được bảo hiểm.', 'warn');
      else if (err.code === 'POLICY_NOT_MODIFIABLE') toast.push('Hợp đồng không ở trạng thái nhận bồi thường.', 'warn');
    }
  };

  return (
    <form className="card stack" onSubmit={submit}>
      <ErrorBanner error={error} />
      <div className="form-grid">
        <SelectField label="Hợp đồng" value={policyId} onChange={setPolicyId}
          options={policies.map((p) => p.policy_id)} labelFn={(id) => { const p = policies.find((x) => x.policy_id === id); return p ? viProduct(p.product_id) : id; }} required />
        <SelectField label="Loại tổn thất" value={lossType} onChange={setLossType} options={LOSS_TYPES} labelFn={viEnum} placeholder="Chọn…" required />
        <TextField label="Thời điểm xảy ra" type="datetime-local" value={occurrence} onChange={setOccurrence} required />
        <NumberField label="Chi phí ước tính (₫)" value={cost} onChange={setCost} min={0} required />
      </div>
      <TextAreaField label="Mô tả" value={description} onChange={setDescription} placeholder="Mô tả ngắn gọn tổn thất" />
      <button className="btn btn-primary" disabled={busy}>{busy ? <Spinner /> : 'Gửi yêu cầu'}</button>
    </form>
  );
}
