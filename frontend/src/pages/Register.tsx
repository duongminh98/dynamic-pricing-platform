import { useState } from 'react';
import { apiFetch } from '../api/client';

export default function Register() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [msg, setMsg] = useState('');
  const submit = async () => {
    try {
      await apiFetch('/customers/register', { method: 'POST', body: JSON.stringify({ email, password }) });
      setMsg('Registered. You can now log in.');
    } catch (e) {
      setMsg('Registration failed: ' + (e as Error).message);
    }
  };
  return (
    <div>
      <h2>Register</h2>
      <input placeholder='email' onChange={e => setEmail(e.target.value)} />
      <input type='password' placeholder='password' onChange={e => setPassword(e.target.value)} />
      <button onClick={submit}>Register</button>
      {msg && <p>{msg}</p>}
    </div>
  );
}
