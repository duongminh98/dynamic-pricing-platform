import { createContext, useContext, useState, ReactNode } from 'react';
import { setToken, clearToken, getToken } from '../api/client';

interface AuthCtx { isLoggedIn: boolean; login: (t: string) => void; logout: () => void; }
const Ctx = createContext<AuthCtx>(null!);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isLoggedIn, setLoggedIn] = useState(!!getToken());
  return <Ctx.Provider value={{ isLoggedIn, login: (t) => { setToken(t); setLoggedIn(true); }, logout: () => { clearToken(); setLoggedIn(false); } }}>{children}</Ctx.Provider>;
}

export function useAuth() { return useContext(Ctx); }
