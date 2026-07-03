import { createContext, useContext, useState, ReactNode } from 'react';
import { setToken, setIdToken, clearToken, getToken, getIdToken, getStoredRoles, setStoredRoles } from '../api/client';
import { clearPendingLoginRequest, clearLoginReturnTo, keycloakLogoutUrl, setLoggingOut } from './oidc';

interface LoginResult {
  access_token: string;
  id_token?: string;
  roles: string[];
}

interface AuthCtx {
  isLoggedIn: boolean;
  isAdmin: boolean;
  isCustomer: boolean;
  roles: string[];
  login: (r: LoginResult) => string[];
  logout: () => void;
}
const Ctx = createContext<AuthCtx>(null!);

/** Decode realm_access.roles from the JWT as a fallback when the login
 * response omits roles. Signature is verified at the gateway, not here. */
function rolesFromToken(token: string | null): string[] {
  if (!token) return [];
  try {
    const payload = token.split('.')[1];
    const json = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
    const realm = json.realm_access || {};
    return Array.isArray(realm.roles) ? realm.roles : [];
  } catch {
    return [];
  }
}

function isTokenUsable(token: string | null): boolean {
  if (!token) return false;
  try {
    const payload = token.split('.')[1];
    const json = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
    if (!json.exp) return false;
    return json.exp * 1000 > Date.now() + 30_000;
  } catch {
    return false;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const forceLoggedOut = new URLSearchParams(location.search).has('logged_out');
  if (forceLoggedOut) {
    clearPendingLoginRequest();
    clearLoginReturnTo();
    clearToken();
  }
  const initialToken = forceLoggedOut ? null : getToken();
  const initialLoggedIn = isTokenUsable(initialToken);
  if (!initialLoggedIn && initialToken) clearToken();
  const [roles, setRoles] = useState<string[]>(initialLoggedIn ? getStoredRoles() : []);
  const [isLoggedIn, setLoggedIn] = useState(initialLoggedIn);

  const login = (r: LoginResult): string[] => {
    setToken(r.access_token);
    setIdToken(r.id_token ?? null);
    const resolved = r.roles && r.roles.length ? r.roles : rolesFromToken(r.access_token);
    setStoredRoles(resolved);
    setRoles(resolved);
    setLoggedIn(true);
    return resolved;
  };
  const logout = () => {
    const idToken = getIdToken();
    // Navigate straight to Keycloak logout. We deliberately do NOT flip React state
    // first: setLoggedIn(false) would synchronously re-render the current protected
    // route into <AuthRedirect>, whose effect fires its own location.replace() to the
    // Keycloak *login* endpoint — racing (and overriding) this logout redirect, which
    // is exactly why "logout" left the user on the same tab. The page is leaving, so
    // there is nothing to update in state anyway.
    setLoggingOut();
    clearPendingLoginRequest();
    clearLoginReturnTo();
    clearToken();
    location.replace(keycloakLogoutUrl(idToken));
  };

  return (
    <Ctx.Provider
      value={{
        isLoggedIn,
        isAdmin: roles.includes('Administrator'),
        isCustomer: roles.includes('Customer'),
        roles,
        login,
        logout,
      }}
    >
      {children}
    </Ctx.Provider>
  );
}

export function useAuth() {
  return useContext(Ctx);
}






