const BASE_URL = 'http://localhost:8000';

export function getToken(): string | null {
  return localStorage.getItem('access_token');
}

export function setToken(token: string): void {
  localStorage.setItem('access_token', token);
}

export function clearToken(): void {
  localStorage.removeItem('access_token');
}

export async function apiFetch(path: string, options: RequestInit = {}): Promise<any> {
  const token = getToken();
  const correlationId = crypto.randomUUID();
  const headers: Record<string, string> = { ...((options.headers as Record<string,string>) || {}) };
  if (token) headers['Authorization'] = 'Bearer ' + token;
  headers['X-Correlation-Id'] = correlationId;
  if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE_URL + path, { ...options, headers });
  if (res.status === 401) { clearToken(); window.location.href = '/login'; }
  if (!res.ok) throw new Error('API error ' + res.status);
  return res.json();
}
