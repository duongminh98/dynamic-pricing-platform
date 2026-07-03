const BASE_URL = (import.meta.env.VITE_API_BASE as string) || 'http://localhost:8000';
const TOKEN_KEY = 'access_token';
const ID_TOKEN_KEY = 'id_token';
const ROLES_KEY = 'roles';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}
export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}
export function getIdToken(): string | null {
  return localStorage.getItem(ID_TOKEN_KEY);
}
export function setIdToken(token: string | null): void {
  if (token) localStorage.setItem(ID_TOKEN_KEY, token);
  else localStorage.removeItem(ID_TOKEN_KEY);
}
export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(ID_TOKEN_KEY);
  localStorage.removeItem(ROLES_KEY);
}
export function getStoredRoles(): string[] {
  try {
    const raw = localStorage.getItem(ROLES_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}
export function setStoredRoles(roles: string[]): void {
  localStorage.setItem(ROLES_KEY, JSON.stringify(roles));
}

/** Mirrors the backend error envelope. `code` drives UI logic, not `message`. */
export class ApiError extends Error {
  status: number;
  code: string;
  details: Record<string, any> | null;
  correlationId: string | null;
  constructor(status: number, code: string, message: string, details: any, correlationId: string | null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details ?? null;
    this.correlationId = correlationId;
  }
}

export interface FetchOpts extends Omit<RequestInit, 'body'> {
  body?: unknown;
  /** when true, a 401 will NOT auto-redirect (used on the login call itself) */
  noAuthRedirect?: boolean;
}

/**
 * Guards the 401 → re-login redirect against infinite loops.
 *
 * A 401 normally means the session expired, so we bounce to Keycloak to refresh it.
 * But if the brand-new token that comes back *still* yields a 401 (e.g. the identity
 * can't be provisioned, or a business path mis-reports 401), a naive redirect loops
 * forever: page → Keycloak → callback → page → 401 → Keycloak → …
 *
 * We stamp the moment we start a re-auth. If another 401 arrives within the window,
 * re-authenticating clearly didn't help, so we stop and land on a terminal screen.
 * Any successful response clears the stamp, so a later genuine expiry re-auths normally.
 */
const REAUTH_STAMP_KEY = 'oidc_reauth_started_at';
const REAUTH_LOOP_WINDOW_MS = 20_000;

function clearReauthStamp(): void {
  sessionStorage.removeItem(REAUTH_STAMP_KEY);
}

function reauthLoopDetected(): boolean {
  const raw = sessionStorage.getItem(REAUTH_STAMP_KEY);
  if (!raw) return false;
  const started = Number(raw);
  return Number.isFinite(started) && Date.now() - started < REAUTH_LOOP_WINDOW_MS;
}

export async function apiFetch<T = any>(path: string, opts: FetchOpts = {}): Promise<T> {
  const { body, noAuthRedirect, headers: hdrs, ...rest } = opts;
  const token = getToken();
  const headers: Record<string, string> = { Accept: 'application/json', ...(hdrs as Record<string, string>) };
  headers['X-Correlation-Id'] = crypto.randomUUID();
  if (token) headers['Authorization'] = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const res = await fetch(BASE_URL + path, {
    ...rest,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (res.status === 401 && !noAuthRedirect) {
    if (reauthLoopDetected()) {
      // Re-authenticating did not clear the 401 — stop bouncing and show a terminal
      // screen instead of looping the browser between the app and Keycloak.
      clearReauthStamp();
      clearToken();
      if (!location.pathname.startsWith('/login')) location.replace('/login?auth_error=1');
      throw new ApiError(401, 'UNAUTHENTICATED', 'Session could not be established', null, null);
    }
    clearToken();
    if (!location.pathname.startsWith('/login')) {
      sessionStorage.setItem(REAUTH_STAMP_KEY, String(Date.now()));
      const returnToPath = location.pathname + location.search;
      void import('../auth/oidc')
        .then(({ redirectCurrentPageToKeycloak }) => redirectCurrentPageToKeycloak(returnToPath))
        .catch(() => location.replace('/login'));
    }
    throw new ApiError(401, 'UNAUTHENTICATED', 'Session expired', null, null);
  }

  // Any non-401 response proves the current token works — reset the loop guard so a
  // later genuine expiry triggers a fresh re-auth.
  clearReauthStamp();

  const correlationId = res.headers.get('X-Correlation-Id');
  if (res.status === 204) return undefined as T;

  const text = await res.text();
  const data = text ? safeJson(text) : null;

  if (!res.ok) {
    const env = (data && typeof data === 'object' ? data : {}) as any;
    throw new ApiError(
      res.status,
      env.error_code || 'UNKNOWN',
      env.message || res.statusText || 'Request failed',
      env.details,
      env.correlation_id || correlationId,
    );
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** Build a querystring from a params object, skipping null/undefined/''. */
export function qs(params: Record<string, unknown>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === null || v === undefined || v === '') continue;
    u.set(k, String(v));
  }
  const s = u.toString();
  return s ? '?' + s : '';
}



