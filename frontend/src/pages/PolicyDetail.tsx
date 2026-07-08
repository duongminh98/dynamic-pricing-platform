import { useEffect, useRef, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { ApiError, apiFetch } from '../api/client';
import { useApi, useMutation, useInterval, vndLabel, dateOnly, dateTime } from '../lib/format';
import { viFeature, viProduct } from '../lib/labels';
import { LINE_FIELDS, LINE_LABEL, LINE_ICON, AttrField, Line } from '../lib/domain';
import { TextField, TextAreaField, NumberField, SelectField, Toggle } from '../lib/fields';
import { Loading, ErrorBanner, StatusPill, Spinner, Alert, EmptyState, useToast } from '../lib/ui';

interface PolicyResponse {
  policy_id: string; order_id: string; product_id: string; line: string | null; status: string;
  policy_effective_date: string; policy_expiration_date: string; renewal_number: number;
  final_premium_vnd: number; cancel_date: string | null;
}
interface DocResp { version: number; content: string; created_at: string; }
interface Segment {
  exposure_segment_seq: number; segment_start: string; segment_end: string;
  coverage_amount_vnd: number; deductible_vnd: number; earned_exposure_years: number;
}
interface Invoice { invoice_id: string; amount_vnd: number; net_amount_vnd: number; status: string; created_at: string; }
interface Credit { credit_id: string; original_amount_vnd: number; remaining_amount_vnd: number; status: string; }
interface Refund {
  refund_id: string; amount_vnd: number; status: string;
  payment_reference: string | null; completed_at: string | null;
}
interface Billing { invoices: Invoice[]; credits: Credit[]; refunds?: Refund[]; balance_vnd: number; }

type Panel = 'none' | 'renew' | 'cancel' | 'endorse';

export default function PolicyDetail() {
  const { id } = useParams();
  const { data: policy, error, loading, reload } = useApi<PolicyResponse>(`/policies/${id}`, [id]);
  const { data: doc } = useApi<DocResp>(id ? `/policies/${id}/document` : null, [id]);
  const { data: segments } = useApi<Segment[]>(id ? `/policies/${id}/exposure-segments` : null, [id]);
  const { data: billing, reload: reloadBilling } = useApi<Billing>(id ? `/billing/policies/${id}/billing` : null, [id]);
  const [panel, setPanel] = useState<Panel>('none');
  const toast = useToast();
  const { run: runPay, busy: paying } = useMutation();
  const [settleTries, setSettleTries] = useState(0);

  // After returning from VNPAY the invoice clears + the endorsement applies
  // asynchronously, so poll briefly while any invoice is still unpaid.
  const hasUnpaid = !!billing?.invoices.some((i) => i.status === 'unpaid');
  useInterval(() => {
    setSettleTries((n) => n + 1);
    reload();
    reloadBilling();
  }, hasUnpaid && settleTries < 15 ? 3000 : null);

  const payInvoice = async (invoiceId: string) => {
    try {
      const res = await runPay<{ payment_url: string; vnp_txn_ref: string }>(
        `/billing/invoices/${invoiceId}/payment-url`, { method: 'POST', body: {} },
      );
      sessionStorage.setItem('vnp_txn_ref', res.vnp_txn_ref);
      sessionStorage.setItem('vnp_policy_id', id ?? '');
      sessionStorage.removeItem('vnp_order_id');
      window.location.href = res.payment_url;
    } catch (e) {
      const err = e as ApiError;
      if (err.code === 'INVOICE_NOT_PAYABLE') toast.push('Hóa đơn không còn ở trạng thái chờ thanh toán.', 'warn');
      else if (err.code === 'SERVICE_UNAVAILABLE') toast.push('Cổng thanh toán tạm thời không khả dụng. Thử lại sau.', 'warn');
      else toast.push(err.message, 'err');
    }
  };

  if (loading && !policy) return <Loading />;
  if (error) return <ErrorBanner error={error} />;
  if (!policy) return null;

  const cert = parseCert(doc?.content);
  const active = policy.status === 'active';

  return (
    <div className="stack" style={{ maxWidth: 820 }}>
      <Link to="/policies" className="btn-link">← Hợp đồng của tôi</Link>

      <div className="card stack">
        <div className="row-between">
          <div>
            <span className="prod-line">{policy.line ? `${LINE_ICON[policy.line as Line]} ${LINE_LABEL[policy.line as Line]}` : '—'}</span>
            <h2 style={{ fontSize: 'var(--step-2)', marginTop: 4 }}>{viProduct(policy.product_id)}</h2>
            <span className="mono faint" style={{ fontSize: '0.78rem' }}>{policy.policy_id}</span>
          </div>
          <StatusPill status={policy.status} />
        </div>

        <div className="panel">
          <div className="kv" style={{ borderTop: 'none' }}><span className="kv-k">Phí bảo hiểm</span><span className="kv-v">{vndLabel(policy.final_premium_vnd)}</span></div>
          <div className="kv"><span className="kv-k">Hiệu lực</span><span className="kv-v">{dateOnly(policy.policy_effective_date)}</span></div>
          <div className="kv"><span className="kv-k">Hết hạn</span><span className="kv-v">{dateOnly(policy.policy_expiration_date)}</span></div>
          {cert && <div className="kv"><span className="kv-k">Số tiền bảo hiểm</span><span className="kv-v">{vndLabel(cert.coverage_amount_vnd)}</span></div>}
          {cert && <div className="kv"><span className="kv-k">Mức miễn thường</span><span className="kv-v">{vndLabel(cert.deductible_vnd)}</span></div>}
          {policy.cancel_date && <div className="kv"><span className="kv-k">Ngày hủy</span><span className="kv-v">{dateOnly(policy.cancel_date)}</span></div>}
        </div>

        {active && (
          <div className="row wrap">
            <button className="btn btn-ghost" onClick={() => setPanel(panel === 'renew' ? 'none' : 'renew')}>↻ Tái tục</button>
            <button className="btn btn-ghost" onClick={() => setPanel(panel === 'endorse' ? 'none' : 'endorse')}>✎ Sửa đổi</button>
            <button className="btn btn-danger" onClick={() => setPanel(panel === 'cancel' ? 'none' : 'cancel')}>Hủy hợp đồng</button>
          </div>
        )}

        {panel === 'renew' && <RenewPanel policyId={policy.policy_id} onDone={() => { setPanel('none'); reload(); }} />}
        {panel === 'cancel' && <CancelPanel policyId={policy.policy_id} expiration={policy.policy_expiration_date} onDone={() => { setPanel('none'); reload(); }} />}
        {panel === 'endorse' && <EndorsePanel policyId={policy.policy_id} line={policy.line as Line | null} onDone={() => setPanel('none')} />}
      </div>

      {/* certificate */}
      {cert && (
        <div className="card stack">
          <div className="row-between">
            <h3 style={{ fontSize: 'var(--step-1)' }}>Chứng nhận bảo hiểm</h3>
            <div className="row">
              <span className="tag mono">v{cert.version}</span>
              <button className="btn btn-ghost btn-sm" onClick={() => window.print()}>In / Lưu PDF</button>
            </div>
          </div>
          {cert.change && (
            <div className="panel">
              <div className="eyebrow" style={{ marginBottom: 8 }}>Thay đổi ở phiên bản này</div>
              {Object.entries(cert.change).map(([k, v]: [string, any]) => (
                <div className="kv" key={k}><span className="kv-k">{viFeature(k)}</span><span className="kv-v">{vndLabel(v.old)} → {vndLabel(v.new)}</span></div>
              ))}
            </div>
          )}
          <div className="field-hint">Phát hành lúc {dateTime(cert.issued_at)}</div>
        </div>
      )}

      {/* exposure segments */}
      {segments && segments.length > 0 && (
        <div className="card stack">
          <h3 style={{ fontSize: 'var(--step-1)' }}>Khoảng phơi nhiễm</h3>
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>#</th><th>Bắt đầu</th><th>Kết thúc</th><th>Năm phơi nhiễm</th><th>STBH</th></tr></thead>
              <tbody>
                {segments.map((s) => (
                  <tr key={s.exposure_segment_seq}>
                    <td className="mono">{s.exposure_segment_seq}</td>
                    <td>{dateOnly(s.segment_start)}</td>
                    <td>{dateOnly(s.segment_end)}</td>
                    <td className="num">{s.earned_exposure_years.toFixed(2)}</td>
                    <td className="num">{vndLabel(s.coverage_amount_vnd)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* billing: invoices + credits + refunds + balance */}
      {billing && (
        <div className="card stack">
          <div className="row-between">
            <h3 style={{ fontSize: 'var(--step-1)' }}>Thanh toán & tín dụng</h3>
            <span className="derived">Số dư <b>{vndLabel(billing.balance_vnd)}</b></span>
          </div>

          {billing.invoices.length > 0 && (
            <div>
              <div className="eyebrow" style={{ marginBottom: 8 }}>Hóa đơn</div>
              <div className="table-wrap">
                <table className="table">
                  <thead><tr><th>Mã</th><th>Số tiền</th><th>Phải trả</th><th>Trạng thái</th><th>Ngày tạo</th><th></th></tr></thead>
                  <tbody>
                    {billing.invoices.map((inv) => (
                      <tr key={inv.invoice_id}>
                        <td className="mono" style={{ fontSize: '0.78rem' }}>{inv.invoice_id.slice(0, 8)}</td>
                        <td className="num">{vndLabel(inv.amount_vnd)}</td>
                        <td className="num">{vndLabel(inv.net_amount_vnd || inv.amount_vnd)}</td>
                        <td><StatusPill status={inv.status} /></td>
                        <td className="muted">{dateOnly(inv.created_at)}</td>
                        <td className="num">
                          {inv.status === 'unpaid' && (
                            <button className="btn btn-primary btn-sm" disabled={paying} onClick={() => payInvoice(inv.invoice_id)}>
                              {paying ? <Spinner /> : 'Thanh toán qua VNPAY →'}
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {billing.credits && billing.credits.length > 0 && (
            <div>
              <div className="eyebrow" style={{ marginBottom: 8 }}>Tín dụng phí</div>
              {billing.credits.map((c) => (
                <div className="kv" key={c.credit_id}>
                  <span className="kv-k">Còn lại {vndLabel(c.remaining_amount_vnd)} / {vndLabel(c.original_amount_vnd)}</span>
                  <span className="kv-v"><StatusPill status={c.status} /></span>
                </div>
              ))}
            </div>
          )}

          {billing.refunds && billing.refunds.length > 0 && (
            <div>
              <div className="eyebrow" style={{ marginBottom: 8 }}>Hoàn tiền</div>
              {billing.refunds.map((r) => (
                <div className="panel" key={r.refund_id} style={{ marginTop: 8 }}>
                  <div className="kv" style={{ borderTop: 'none' }}>
                  <span className="kv-k mono" style={{ fontSize: '0.8rem' }}>{r.refund_id.slice(0, 8)} · {vndLabel(r.amount_vnd)}</span>
                  <span className="kv-v"><StatusPill status={r.status} /></span>
                  </div>
                  {r.payment_reference && <div className="kv"><span className="kv-k">Mã giao dịch</span><span className="kv-v mono">{r.payment_reference}</span></div>}
                  {r.completed_at && <div className="kv"><span className="kv-k">Hoàn tất lúc</span><span className="kv-v">{dateTime(r.completed_at)}</span></div>}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function parseCert(content?: string): any | null {
  if (!content) return null;
  try { return JSON.parse(content); } catch { return null; }
}

/* ---------- renew ---------- */
function RenewPanel({ policyId, onDone }: { policyId: string; onDone: () => void }) {
  const toast = useToast();
  const { data: preview, error, loading } = useApi<any>(`/policies/${policyId}/renew/preview`, [policyId]);
  const { run, busy } = useMutation();

  const confirm = async () => {
    try {
      const r = await run<any>(`/policies/${policyId}/renew`, { method: 'POST', body: {} });
      toast.push(r.payment_required ? 'Đã tạo tái tục, cần thanh toán.' : 'Tái tục thành công.');
      onDone();
    } catch (e) {
      const err = e as ApiError;
      if (err.code === 'RENEWAL_IN_PROGRESS') toast.push('Đã có yêu cầu tái tục cho kỳ tới.', 'warn');
      else if (err.code === 'POLICY_NOT_MODIFIABLE') toast.push('Hợp đồng không ở trạng thái tái tục được.', 'warn');
      else toast.push(err.message, 'err');
    }
  };

  return (
    <div className="panel stack">
      {loading && <Loading label="Đang tính phí tái tục…" />}
      <ErrorBanner error={error} />
      {preview && (
        <>
          <div className="kv" style={{ borderTop: 'none' }}><span className="kv-k">Phí hiện tại</span><span className="kv-v">{vndLabel(preview.current_premium_vnd)}</span></div>
          <div className="kv"><span className="kv-k">Phí tái tục</span><span className="kv-v">{vndLabel(preview.renewed_premium_vnd)}</span></div>
          <div className="kv"><span className="kv-k">Tín dụng áp dụng</span><span className="kv-v">−{vndLabel(preview.credit_applied_vnd)}</span></div>
          <div className="kv"><span className="kv-k">Phải trả</span><span className="kv-v" style={{ fontWeight: 700 }}>{vndLabel(preview.net_due_vnd)}</span></div>
          <div className="field-hint">Kỳ mới: {dateOnly(preview.new_effective_date)} → {dateOnly(preview.new_expiration_date)}</div>
          <button className="btn btn-primary" disabled={busy} onClick={confirm}>{busy ? <Spinner /> : 'Xác nhận tái tục'}</button>
        </>
      )}
    </div>
  );
}

/* ---------- cancel ---------- */
function CancelPanel({ policyId, expiration, onDone }: { policyId: string; expiration: string; onDone: () => void }) {
  const toast = useToast();
  const { run, busy, error } = useMutation();
  const [reason, setReason] = useState('');

  const submit = async () => {
    try {
      const r = await run<any>(`/policies/${policyId}/cancel`, {
        method: 'POST', body: { reason },
      });
      toast.push(r.refundable_credit_vnd > 0 ? `Đã hủy. Tín dụng hoàn lại: ${vndLabel(r.refundable_credit_vnd)}` : 'Đã hủy hợp đồng.');
      onDone();
    } catch (e) {
      const err = e as ApiError;
      if (err.code === 'CANCEL_DATE_OUT_OF_RANGE') toast.push('Ngày hủy phải nằm trong thời hạn hợp đồng và không ở quá khứ.', 'warn');
      else toast.push(err.message, 'err');
    }
  };

  return (
    <div className="panel stack">
      <Alert kind="warn">Hủy hợp đồng sẽ cắt khoảng phơi nhiễm còn lại. Hành động này không thể hoàn tác.</Alert>
      <ErrorBanner error={error} />
      <div className="field-hint">Hủy có hiệu lực ngay khi xác nhận. Hạn hợp đồng: {dateOnly(expiration)}.</div>
      <TextAreaField label="Lý do" value={reason} onChange={setReason} placeholder="Ví dụ: Đã bán xe" />
      <button className="btn btn-danger" disabled={busy} onClick={submit}>{busy ? <Spinner /> : 'Xác nhận hủy'}</button>
    </div>
  );
}

/* ---------- endorse ---------- */
function EndorsePanel({ policyId, line, onDone }: { policyId: string; line: Line | null; onDone: () => void }) {
  const toast = useToast();
  const { run, busy, error } = useMutation();
  const fields = line ? LINE_FIELDS[line] : [];
  const { data: baseline } = useApi<Record<string, string | number | boolean>>(`/policies/${policyId}/risk-profile`, [policyId]);
  const [change, setChange] = useState<Record<string, string | number | boolean>>({});
  const [preview, setPreview] = useState<any>(null);
  const [pollingPreviewId, setPollingPreviewId] = useState<string | null>(null);
  const pollingPreviewIdRef = useRef<string | null>(null);
  const [pollAttempts, setPollAttempts] = useState(0);
  const [pollError, setPollError] = useState<ApiError | null>(null);

  // The change set stays a pure delta - only fields the customer actually edits. Untouched
  // fields display their current value from the baseline but are never sent, so the backend
  // merges the delta onto the stored risk profile and prior conditions are preserved.
  const base = baseline ?? {};
  const fieldValue = (key: string) => (change[key] !== undefined ? change[key] : base[key]);
  const buildBody = () => ({
    change,
  });
  const hasChange = Object.keys(change).length > 0;
  const previewPending = Boolean(preview && preview.status === 'PRICING_PENDING' && pollingPreviewId);
  const previewReady = Boolean(preview && preview.status === 'PRICED');
  const canSubmit = hasChange && !busy && previewReady;

  useEffect(() => {
    pollingPreviewIdRef.current = pollingPreviewId;
  }, [pollingPreviewId]);

  useInterval(() => {
    if (!pollingPreviewId) return;
    const requestId = pollingPreviewId;
    setPollAttempts((n) => n + 1);
    apiFetch<any>(`/policies/${policyId}/endorsements/preview/${requestId}`)
      .then((next) => {
        if (requestId !== pollingPreviewIdRef.current) return;
        setPreview(next);
        setPollError(null);
        if (next.status === 'PRICED' || next.status === 'PRICING_FAILED') {
          pollingPreviewIdRef.current = null;
          setPollingPreviewId(null);
        }
      })
      .catch((e) => {
        if (requestId !== pollingPreviewIdRef.current) return;
        const err = e instanceof ApiError ? e : new ApiError(0, 'NETWORK', String(e), null, null);
        setPollError(err);
        pollingPreviewIdRef.current = null;
        setPollingPreviewId(null);
      });
  }, pollingPreviewId ? 1500 : null);

  useEffect(() => {
    if (!pollingPreviewId || pollAttempts < 20) return;
    pollingPreviewIdRef.current = null;
    setPollingPreviewId(null);
    setPollError(new ApiError(408, 'PRICING_TIMEOUT', 'Tính phí mất nhiều thời gian hơn dự kiến. Vui lòng thử lại.', null, null));
  }, [pollingPreviewId, pollAttempts]);

  const doPreview = async () => {
    try {
      setPollError(null);
      pollingPreviewIdRef.current = null;
      setPollingPreviewId(null);
      setPollAttempts(0);
      const first = await run<any>(`/policies/${policyId}/endorsements/preview`, { method: 'POST', body: buildBody() });
      setPreview(first);
      if (first.status === 'PRICING_PENDING' && first.pricing_request_id) {
        pollingPreviewIdRef.current = first.pricing_request_id;
        setPollingPreviewId(first.pricing_request_id);
      }
    }
    catch { /* shown */ }
  };
  const submit = async () => {
    try {
      if (!previewReady) {
        toast.push('Vui lòng xem trước phí và chờ hệ thống tính xong trước khi gửi.', 'warn');
        return;
      }
      await run(`/policies/${policyId}/endorsements`, { method: 'POST', body: buildBody() });
      toast.push('Đã gửi yêu cầu sửa đổi. Đang chờ duyệt.');
      onDone();
    } catch (e) {
      const err = e as ApiError;
      if (err.code === 'ENDORSEMENT_IN_PROGRESS') toast.push('Đang có một yêu cầu sửa đổi khác.', 'warn');
      else toast.push(err.message, 'err');
    }
  };

  return (
    <div className="panel stack">
      <Alert kind="info">Sửa đổi thuộc tính tài sản (ví dụ giá trị xe). Không thể đổi số tiền bảo hiểm hay mức miễn thường tại đây.</Alert>
      <ErrorBanner error={error || pollError} />
      <div className="field-hint">Sửa đổi có hiệu lực ngay khi được duyệt và hoàn tất thanh toán nếu có phí phải trả.</div>
      {fields.length === 0 ? (
        <Alert kind="warn">Không xác định được dòng sản phẩm của hợp đồng này.</Alert>
      ) : (
        <div className="form-grid">
          {fields.map((f) => (
            <EndorsementField key={f.key} field={f} value={fieldValue(f.key)} onChange={(value) => {
              setChange((prev) => {
                const next = { ...prev };
                if (value === '') delete next[f.key];
                else next[f.key] = value;
                return next;
              });
              setPreview(null);
              pollingPreviewIdRef.current = null;
              setPollingPreviewId(null);
              setPollAttempts(0);
              setPollError(null);
            }} />
          ))}
        </div>
      )}

      {preview && (
        <div className="panel" style={{ background: 'var(--raised)' }}>
          <div className="kv" style={{ borderTop: 'none' }}><span className="kv-k">Phí hiện tại</span><span className="kv-v">{vndLabel(preview.current_premium_vnd)}</span></div>
          {preview.status === 'PRICING_PENDING' ? (
            <div className="row" style={{ marginTop: 10 }}>
              {previewPending && <Spinner />}
              <span className="field-hint">{previewPending ? 'Đang tính phí sau sửa đổi…' : 'Chưa có kết quả phí. Bấm Xem trước phí để thử lại.'}</span>
            </div>
          ) : preview.status === 'PRICING_FAILED' ? (
            <Alert kind="err">Không tính được phí preview{preview.pricing_failed_reason ? `: ${preview.pricing_failed_reason}` : '.'}</Alert>
          ) : (
            <>
              <div className="kv"><span className="kv-k">Phí sau sửa đổi</span><span className="kv-v">{vndLabel(preview.quoted_premium_vnd)}</span></div>
              <div className="kv"><span className="kv-k">Chênh lệch theo tỷ lệ</span><span className="kv-v">{vndLabel(preview.pro_rated_charge_vnd)}</span></div>
            </>
          )}
          <div className="field-hint">Còn {preview.remaining_days}/{preview.term_days} ngày trong kỳ.</div>
        </div>
      )}

      <div className="row">
        <button className="btn btn-ghost" disabled={busy || previewPending || !hasChange} onClick={doPreview}>{(busy && !preview) || previewPending ? <Spinner /> : 'Xem trước phí'}</button>
        <button className="btn btn-primary" disabled={!canSubmit} onClick={submit}>Gửi yêu cầu sửa đổi</button>
      </div>
    </div>
  );
}

function EndorsementField({ field, value, onChange }: { field: AttrField; value: string | number | boolean | undefined; onChange: (value: string | number | boolean | '') => void }) {
  if (field.kind === 'bool') {
    return <Toggle label={field.label} value={Boolean(value)} onChange={onChange} />;
  }
  if (field.kind === 'number') {
    return <NumberField label={field.label} value={typeof value === 'number' ? value : ''} min={field.min} max={field.max} onChange={onChange} />;
  }
  if (field.kind === 'enum') {
    return <SelectField label={field.label} value={typeof value === 'string' ? value : ''} options={field.options ?? []} placeholder="Chọn giá trị" onChange={onChange} />;
  }
  return <TextField label={field.label} type={field.kind === 'date' ? 'date' : 'text'} value={typeof value === 'string' ? value : ''} onChange={onChange} />;
}
