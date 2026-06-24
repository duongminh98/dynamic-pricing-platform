import { createContext, useContext, useState, ReactNode } from 'react';
import { setToken, clearToken, getToken } from '../api/client';

interface AuthCtx {
  isLoggedIn: boolean;
  isAdmin: boolean;
  roles: string[];
  login: (t: string) => void;
  logout: () => void;
}
const Ctx = createContext<AuthCtx>(null!);

/** Decode realm_access.roles from a JWT without verifying the signature
 * (the gateway verifies it). Returns [] on any parse failure. */
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
  const [roles, setRoles] = useState<string[]>(rolesFromToken(getToken()));
  const [isLoggedIn, setLoggedIn] = useState(!!getToken());

  const login = (t: string) => {
    setToken(t);
    setRoles(rolesFromToken(t));
    setLoggedIn(true);
  };
  const logout = () => {
    clearToken();
    setRoles([]);
    setLoggedIn(false);
  };

  return (
    <Ctx.Provider value={{ isLoggedIn, isAdmin: roles.includes('Administrator'), roles, login, logout }}>
      {children}
    </Ctx.Provider>
  );
}

export function useAuth() { return useContext(Ctx); }
