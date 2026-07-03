import { useEffect, useState } from 'react';
import { redirectCurrentPageToKeycloak } from '../auth/oidc';
import { Spinner } from '../lib/ui';

let redirectInFlight = false;

export default function AuthRedirect({ returnTo }: { returnTo: string }) {
  const [error, setError] = useState('');

  useEffect(() => {
    if (redirectInFlight) return;
    redirectInFlight = true;
    redirectCurrentPageToKeycloak(returnTo).catch(() => {
      redirectInFlight = false;
      setError('Unable to start login.');
    });
  }, [returnTo]);

  return (
    <div className="auth-form-wrap">
      <div className="auth-form center">
        {error ? <div className="alert alert-err">{error}</div> : <Spinner />}
      </div>
    </div>
  );
}
