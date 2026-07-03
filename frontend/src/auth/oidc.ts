const KEYCLOAK_URL = (import.meta.env.VITE_KEYCLOAK_URL as string) || 'http://localhost:8080';
const REALM = (import.meta.env.VITE_KEYCLOAK_REALM as string) || 'dynamic-pricing';
const CLIENT_ID = (import.meta.env.VITE_KEYCLOAK_CLIENT_ID as string) || 'mini-app';
const VERIFIER_KEY = 'pkce_verifier';
const RETURN_TO_KEY = 'login_return_to';

// Set the instant a logout redirect starts. Once the browser is navigating to the
// Keycloak logout endpoint, any React re-render that would otherwise kick off a
// login redirect (e.g. a protected route flipping to <AuthRedirect>) must stand down,
// or it races and overrides the logout — leaving the user signed in on the same tab.
let loggingOut = false;
export function setLoggingOut(): void {
  loggingOut = true;
}
export function isLoggingOut(): boolean {
  return loggingOut;
}

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function challenge(verifier: string): Promise<string> {
  const data = new TextEncoder().encode(verifier);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return base64Url(new Uint8Array(digest));
}

function redirectUri(): string {
  return `${location.origin}/auth/callback`;
}

function isSafeReturnPath(path: string | null | undefined): path is string {
  return Boolean(path && path.startsWith('/') && !path.startsWith('//') && !path.startsWith('/login') && !path.startsWith('/register') && !path.startsWith('/auth/callback'));
}

export function setLoginReturnTo(path: string | null | undefined): void {
  if (isSafeReturnPath(path)) sessionStorage.setItem(RETURN_TO_KEY, path);
}

export function takeLoginReturnTo(): string | null {
  const path = sessionStorage.getItem(RETURN_TO_KEY);
  sessionStorage.removeItem(RETURN_TO_KEY);
  return isSafeReturnPath(path) ? path : null;
}

export async function redirectToKeycloak(action: 'login' | 'register' = 'login', forceLogin = false, returnTo?: string | null): Promise<void> {
  if (isLoggingOut()) return; // a logout redirect is in flight — don't race it with a login
  setLoginReturnTo(returnTo);
  const verifier = base64Url(crypto.getRandomValues(new Uint8Array(32)));
  sessionStorage.setItem(VERIFIER_KEY, verifier);

  const endpoint = action === 'register' ? 'registrations' : 'auth';
  const url = new URL(`${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/${endpoint}`);
  url.searchParams.set('client_id', CLIENT_ID);
  url.searchParams.set('redirect_uri', redirectUri());
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('scope', 'openid email profile');
  url.searchParams.set('code_challenge_method', 'S256');
  url.searchParams.set('code_challenge', await challenge(verifier));
  if (forceLogin) url.searchParams.set('prompt', 'login');
  location.replace(url.toString());
}

export function hasPendingLoginRequest(): boolean {
  return Boolean(sessionStorage.getItem(VERIFIER_KEY));
}

export function clearPendingLoginRequest(): void {
  sessionStorage.removeItem(VERIFIER_KEY);
}

export function clearLoginReturnTo(): void {
  sessionStorage.removeItem(RETURN_TO_KEY);
}

export async function exchangeCode(code: string): Promise<{ access_token: string; id_token?: string; roles: string[] }> {
  const verifier = sessionStorage.getItem(VERIFIER_KEY);
  if (!verifier) throw new Error('Missing PKCE verifier');

  const body = new URLSearchParams();
  body.set('grant_type', 'authorization_code');
  body.set('client_id', CLIENT_ID);
  body.set('redirect_uri', redirectUri());
  body.set('code', code);
  body.set('code_verifier', verifier);

  const res = await fetch(`${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  clearPendingLoginRequest();
  if (!res.ok) throw new Error('Token exchange failed');
  const json = await res.json();
  return { access_token: json.access_token, id_token: json.id_token, roles: [] };
}


export async function redirectCurrentPageToKeycloak(returnTo?: string | null, forceLogin = false): Promise<void> {
  await redirectToKeycloak('login', forceLogin, returnTo ?? `${location.pathname}${location.search}`);
}
export function keycloakLogoutUrl(idToken?: string | null): string {
  const url = new URL(`${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/logout`);
  url.searchParams.set('client_id', CLIENT_ID);
  url.searchParams.set('post_logout_redirect_uri', location.origin + '/login?logged_out=1');
  if (idToken) url.searchParams.set('id_token_hint', idToken);
  return url.toString();
}

