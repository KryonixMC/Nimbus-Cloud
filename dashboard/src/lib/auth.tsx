"use client";

import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { useRouter } from "next/navigation";
import { isAuthenticated, clearCredentials } from "./api";

interface AuthContextType {
  authenticated: boolean;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType>({
  authenticated: false,
  logout: () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [authenticated, setAuthenticated] = useState(false);
  const [checked, setChecked] = useState(false);
  const router = useRouter();

  useEffect(() => {
    if (isAuthenticated()) {
      setAuthenticated(true);
    } else {
      router.replace("/login");
    }
    setChecked(true);
  }, [router]);

  function logout() {
    clearCredentials();
    setAuthenticated(false);
    router.replace("/login");
  }

  if (!checked) return null;
  if (!authenticated) return null;

  return (
    <AuthContext.Provider value={{ authenticated, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
