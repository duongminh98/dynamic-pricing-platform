import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { clearPendingLoginRequest, redirectToKeycloak } from '../auth/oidc';
import { Spinner } from '../lib/ui';

let redirectInFlight = false;

export default function Login() {
  const [params] = useSearchParams();
  const [error, setError] = useState('');
  const [starting, setStarting] = useState(false);
  const returnTo = params.get('returnTo') || '/products';
  const loggedOut = params.has('logged_out');
  const authError = params.has('auth_error');
  // Only auth_error is terminal: a re-auth attempt already failed, so auto-redirecting
  // would loop. After a normal logout we go straight back to the Keycloak login screen
  // (prompt=login, so a lingering SSO cookie can't silently sign the user back in) —
  // no intermediate "you are logged out" page.
  const terminal = authError;
  const forceLogin = loggedOut || authError;
  const startedRef = useRef(false);

  const startLogin = () => {
    if (redirectInFlight) return;
    redirectInFlight = true;
    setStarting(true);
    clearPendingLoginRequest();
    // Force a fresh Keycloak login prompt so a lingering SSO cookie can't silently
    // sign the user back in right after they logged out.
    redirectToKeycloak('login', forceLogin, returnTo).catch(() => {
      redirectInFlight = false;
      setStarting(false);
      setError('Unable to start login.');
    });
  };

  useEffect(() => {
    if (terminal || startedRef.current) return;
    startedRef.current = true;
    startLogin();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (terminal) {
    return (
      <div className="auth-form-wrap">
        <div className="auth-form center">
          <div className="brand" style={{ marginBottom: 'var(--s4)' }}>
            <span className="brand-mark">DPP</span>
            <span className="brand-word">Pricing</span>
          </div>
          <div className="alert alert-err" style={{ marginBottom: 'var(--s4)' }}>
            Không thể thiết lập phiên đăng nhập. Vui lòng thử đăng nhập lại.
          </div>
          {error && <div className="alert alert-err" style={{ marginBottom: 'var(--s4)' }}>{error}</div>}
          <button className="btn btn-primary btn-block" onClick={startLogin} disabled={starting}>
            {starting ? <Spinner /> : 'Đăng nhập'}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-form-wrap">
      <div className="auth-form center">
        {error ? <div className="alert alert-err">{error}</div> : <Spinner />}
      </div>
    </div>
  );
}
