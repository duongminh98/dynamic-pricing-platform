import { createContext, useContext, useState, ReactNode } from 'react';
import { setToken, clearToken, getToken, getStoredRoles, setStoredRoles } from '../api/client';

interface LoginResult {
  access_token: string;
  roles: string[];
}

interface AuthCtx {
  isLoggedIn: boolean;
  isAdmin: boolean;
  isCustomer: boolean;
  roles: string[];
  login: (r: LoginResult) => void;
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

export function AuthProvider({ children }: { children: ReactNode }) {
  const [roles, setRoles] = useState<string[]>(getStoredRoles());
  const [isLoggedIn, setLoggedIn] = useState(!!getToken());

  const login = (r: LoginResult) => {
    setToken(r.access_token);
    const resolved = r.roles && r.roles.length ? r.roles : rolesFromToken(r.access_token);
    setStoredRoles(resolved);
    setRoles(resolved);
    setLoggedIn(true);
  };
  const logout = () => {
    clearToken();
    setRoles([]);
    setLoggedIn(false);
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
