import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation } from '../lib/format';
import { TextField } from '../lib/fields';
import { Spinner, useToast } from '../lib/ui';

export default function Register() {
  const nav = useNavigate();
  const toast = useToast();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const { run, busy, error } = useMutation();

  // client-side mirror of the server rules for instant feedback
  const emailErr = email && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) ? 'Email không hợp lệ' : '';
  const passErr = password && (password.length < 8 || password.length > 128) ? 'Mật khẩu cần 8–128 ký tự' : '';

  const fieldErr = (f: string): string | undefined => {
    if (error?.code === 'BAD_REQUEST' && error.details && error.details[f]) return String(error.details[f]);
    if (f === 'email' && error?.code === 'EMAIL_ALREADY_USED') return 'Email đã được đăng ký';
    return undefined;
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (emailErr || passErr) return;
    try {
      await run('/customers/register', { method: 'POST', body: { email, password }, noAuthRedirect: true });
      toast.push('Đăng ký thành công. Hãy đăng nhập.');
      nav('/login', { replace: true, state: { email } });
    } catch {
      /* rendered inline */
    }
  };

  const topError =
    error && error.code === 'INTERNAL_ERROR'
      ? 'Hệ thống tạm thời gặp sự cố. Vui lòng thử lại sau.'
      : error && !['BAD_REQUEST', 'EMAIL_ALREADY_USED'].includes(error.code)
      ? error.message
      : null;

  return (
    <div className="auth">
      <aside className="auth-aside">
        <div className="brand" style={{ marginBottom: 0 }}>
          <span className="brand-mark">DPP</span>
          <span className="brand-word">Pricing</span>
        </div>
        <div>
          <p className="eyebrow" style={{ color: '#7E887E' }}>Bắt đầu</p>
          <h2 className="auth-quote">
            Một hồ sơ. <span className="hl">Sáu dòng bảo hiểm.</span> Giá riêng cho bạn.
          </h2>
          <p className="muted" style={{ color: '#9AA39A', marginTop: 16, maxWidth: 380 }}>
            Tạo tài khoản, khai báo hồ sơ rủi ro một lần, rồi nhận báo giá tức thì cho sức khỏe, xe, nhà, tai nạn và du lịch.
          </p>
        </div>
        <div className="auth-demo">Chỉ tài khoản khách hàng được tự đăng ký. Quản trị viên do hệ thống cấp sẵn.</div>
      </aside>

      <div className="auth-form-wrap">
        <form className="auth-form" onSubmit={submit}>
          <p className="eyebrow">Tài khoản mới</p>
          <h2 style={{ marginBottom: 'var(--s5)' }}>Đăng ký</h2>

          {topError && <div className="alert alert-err" style={{ marginBottom: 'var(--s4)' }}>{topError}</div>}

          <TextField
            label="Email"
            type="email"
            value={email}
            onChange={setEmail}
            placeholder="ban@example.com"
            autoComplete="email"
            required
            error={emailErr || fieldErr('email')}
          />
          <TextField
            label="Mật khẩu"
            type="password"
            value={password}
            onChange={setPassword}
            placeholder="Tối thiểu 8 ký tự"
            autoComplete="new-password"
            required
            error={passErr || fieldErr('password')}
            hint="8–128 ký tự"
          />

          <button className="btn btn-primary btn-block" disabled={busy} style={{ marginTop: 'var(--s2)' }}>
            {busy ? <Spinner /> : 'Tạo tài khoản'}
          </button>

          <p className="muted center" style={{ marginTop: 'var(--s5)', fontSize: '0.9rem' }}>
            Đã có tài khoản? <Link to="/login">Đăng nhập</Link>
          </p>
        </form>
      </div>
    </div>
  );
}
