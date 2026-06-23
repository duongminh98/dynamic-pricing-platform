import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { apiFetch } from '../api/client';

export default function Login() {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const submit = async () => {
    const res = await apiFetch('/customers/login', { method: 'POST', body: JSON.stringify({ email, password }) });
    if (res.access_token) login(res.access_token);
  };
  return <div><h2>Login</h2><input placeholder='email' onChange={e => setEmail(e.target.value)} /><input type='password' placeholder='password' onChange={e => setPassword(e.target.value)} /><button onClick={submit}>Login</button></div>;
}
