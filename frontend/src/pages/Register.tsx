import { useEffect, useState } from 'react';
import { redirectToKeycloak } from '../auth/oidc';
import { Spinner } from '../lib/ui';

export default function Register() {
  const [error, setError] = useState('');

  useEffect(() => {
    redirectToKeycloak('register', false, '/products').catch(() => setError('Unable to start registration.'));
  }, []);

  return (
    <div className="auth-form-wrap">
      <div className="auth-form center">
        <h2>Creating account</h2>
        {error ? <div className="alert alert-err">{error}</div> : <Spinner />}
      </div>
    </div>
  );
}

