import { useState } from 'react';
import { Link, useNavigate, useSearchParams, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useMutation } from '../lib/format';
import { TextField } from '../lib/fields';
import { Spinner } from '../lib/ui';

const DEMO = [
  { role: 'Khách hàng', email: 'demo.customer@example.com', pass: 'demo_customer_dev_only' },
  { role: 'Quản trị', email: 'demo.admin@example.com', pass: 'demo_admin_dev_only' },
];

export default function Login() {
  const { login } = useAuth();
  const nav = useNavigate();
  const [params] = useSearchParams();
  const loc = useLocation();
  const expired = params.get('expired') === '1';

  const [email, setEmail] = useState((loc.state as { email?: string } | null)?.email ?? '');
  const [password, setPassword] = useState('');
  const { run, busy, error } = useMutation();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const res = await run<{ access_token: string; roles: string[] }>('/customers/login', {
        method: 'POST',
        body: { email, password },
        noAuthRedirect: true,
      });
      login(res);
      nav(res.roles?.includes('Administrator') ? '/admin' : '/products', { replace: true });
    } catch {
      /* error rendered below */
    }
  };

  const banner = (() => {
    if (!error) return expired ? { kind: 'warn', text: 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.' } : null;
    if (error.code === 'INVALID_CREDENTIALS') return { kind: 'err', text: 'Email hoặc mật khẩu không đúng.' };
    if (error.code === 'ACCOUNT_LOCKED') return { kind: 'err', text: 'Tài khoản tạm khóa do nhập sai nhiều lần. Thử lại sau khoảng 15 phút.' };
    if (error.code === 'BAD_REQUEST') return { kind: 'err', text: 'Vui lòng nhập đầy đủ email và mật khẩu.' };
    return { kind: 'err', text: error.message };
  })();

  return (
    <div className="auth">
      <aside className="auth-aside">
        <div className="brand" style={{ marginBottom: 0 }}>
          <span className="brand-mark">DPP</span>
          <span className="brand-word">Pricing</span>
        </div>
        <div>
          <p className="eyebrow" style={{ color: '#7E887E' }}>Định giá bảo hiểm động</p>
          <h2 className="auth-quote">
            Mỗi mức phí đều <span className="hl">cho bạn xem cách nó được tính.</span>
          </h2>
          <p className="muted" style={{ color: '#9AA39A', marginTop: 16, maxWidth: 380 }}>
            Mô hình rủi ro chấm điểm hồ sơ của bạn, rồi phân rã từng yếu tố đẩy giá lên hay kéo giá xuống — minh bạch, không hộp đen.
          </p>
        </div>
        <div className="auth-demo">
          <div style={{ marginBottom: 8 }}>Tài khoản demo (chỉ môi trường dev):</div>
          {DEMO.map((d) => (
            <div key={d.email} style={{ marginBottom: 6 }}>
              <b>{d.role}</b> · {d.email}
              <br />
              <span style={{ opacity: 0.7 }}>{d.pass}</span>
            </div>
          ))}
        </div>
      </aside>

      <div className="auth-form-wrap">
        <form className="auth-form" onSubmit={submit}>
          <p className="eyebrow">Chào mừng trở lại</p>
          <h2 style={{ marginBottom: 'var(--s5)' }}>Đăng nhập</h2>

          {banner && (
            <div className={`alert alert-${banner.kind}`} style={{ marginBottom: 'var(--s4)' }}>
              {banner.text}
            </div>
          )}

          <TextField label="Email" type="email" value={email} onChange={setEmail} placeholder="ban@example.com" autoComplete="email" required />
          <TextField label="Mật khẩu" type="password" value={password} onChange={setPassword} placeholder="••••••••" autoComplete="current-password" required />

          <button className="btn btn-primary btn-block" disabled={busy} style={{ marginTop: 'var(--s2)' }}>
            {busy ? <Spinner /> : 'Đăng nhập'}
          </button>

          <p className="muted center" style={{ marginTop: 'var(--s5)', fontSize: '0.9rem' }}>
            Chưa có tài khoản? <Link to="/register">Đăng ký</Link>
          </p>
        </form>
      </div>
    </div>
  );
}
