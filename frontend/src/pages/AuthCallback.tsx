import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { clearPendingLoginRequest, exchangeCode, hasPendingLoginRequest, takeLoginReturnTo } from '../auth/oidc';
import { apiFetch } from '../api/client';
import { Spinner } from '../lib/ui';

const PROCESSING_CODE_KEY = 'oidc_processing_code';

export default function AuthCallback() {
  const { login } = useAuth();
  const nav = useNavigate();
  const [params] = useSearchParams();
  const [error, setError] = useState('');

  useEffect(() => {
    const code = params.get('code');
    if (!code || !hasPendingLoginRequest()) {
      clearPendingLoginRequest();
      setError('Unable to complete login. Please try again.');
      return;
    }
    if (sessionStorage.getItem(PROCESSING_CODE_KEY) === code) return;
    sessionStorage.setItem(PROCESSING_CODE_KEY, code);

    exchangeCode(code)
      .then(async (result) => {
        sessionStorage.removeItem(PROCESSING_CODE_KEY);
        const roles = login(result);
        // Just-in-time provision the Keycloak identity into customer-service so the
        // very first protected call (profile, quote) already has a backing account.
        // /customers/me both bootstraps the account and echoes the authoritative roles.
        await apiFetch('/customers/me', { noAuthRedirect: true }).catch(() => undefined);
        const fallback = roles.includes('Administrator') ? '/admin' : '/products';
        const next = takeLoginReturnTo() ?? fallback;
        const allowed = roles.includes('Administrator') ? next.startsWith('/admin') : !next.startsWith('/admin');
        nav(allowed ? next : fallback, { replace: true });
      })
      .catch(() => {
        sessionStorage.removeItem(PROCESSING_CODE_KEY);
        clearPendingLoginRequest();
        setError('Unable to complete login. Please try again.');
      });
  }, []);

  return (
    <div className="auth-form-wrap">
      <div className="auth-form center">
        {error ? <div className="alert alert-err">{error}</div> : <Spinner />}
      </div>
    </div>
  );
}
