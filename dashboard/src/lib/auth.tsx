"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useRouter } from "next/navigation";
import {
  apiFetch,
  clearCredentials,
  fetchMe,
  getApiUrl,
  getAuthKind,
  getToken,
  type UserInfo,
} from "./api";

/**
 * Discriminated union that reflects every valid bootstrap outcome. Callers
 * should switch on `kind` rather than coercing to a boolean.
 */
export type AuthState =
  | { kind: "loading" }
  | { kind: "anonymous" }
  | { kind: "api-token" }
  | { kind: "user"; user: UserInfo };

interface AuthContextValue {
  state: AuthState;
  /** Legacy boolean for existing call sites; true for api-token + user. */
  authenticated: boolean;
  /** Current user info when `kind === "user"`, else `null`. */
  user: UserInfo | null;
  hasPermission: (node: string) => boolean;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue>({
  state: { kind: "loading" },
  authenticated: false,
  user: null,
  hasPermission: () => false,
  refresh: async () => {},
  logout: async () => {},
});

const ADMIN_SHORTCUT = "nimbus.dashboard.admin";
const REFRESH_INTERVAL_MS = 5 * 60 * 1000;

/**
 * Backend-compatible wildcard match.
 *
 *   "*"                            → matches anything
 *   "a.b.c"                        → exact match
 *   "a.b.*"                        → matches "a.b.c", "a.b.c.d", ...
 *   "nimbus.dashboard.admin"       → master shortcut
 */
function matchesPermission(granted: string, required: string): boolean {
  if (granted === "*") return true;
  if (granted === required) return true;
  if (granted === ADMIN_SHORTCUT) return true;
  if (granted.endsWith(".*")) {
    const prefix = granted.slice(0, -2);
    return required === prefix || required.startsWith(prefix + ".");
  }
  return false;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ kind: "loading" });
  const router = useRouter();
  const refreshTimer = useRef<ReturnType<typeof setInterval> | null>(null);

  const bootstrap = useCallback(async () => {
    if (!getApiUrl() || !getToken()) {
      clearCredentials();
      setState({ kind: "anonymous" });
      router.replace("/login");
      return;
    }

    const kind = getAuthKind();

    if (kind === "api-token") {
      setState({ kind: "api-token" });
      return;
    }

    // user-session (or legacy creds without a stored kind → treat as session)
    const user = await fetchMe();
    if (user) {
      setState({ kind: "user", user });
    } else {
      clearCredentials();
      setState({ kind: "anonymous" });
      router.replace("/login");
    }
  }, [router]);

  useEffect(() => {
    bootstrap();
  }, [bootstrap]);

  // Silent refresh every 5 min for user sessions — keeps `permissions` and
  // `totpEnabled` fresh and surfaces server-side revocations promptly.
  useEffect(() => {
    if (state.kind !== "user") return;
    refreshTimer.current = setInterval(async () => {
      const user = await fetchMe();
      if (user) {
        setState({ kind: "user", user });
      } else {
        clearCredentials();
        setState({ kind: "anonymous" });
        router.replace("/login");
      }
    }, REFRESH_INTERVAL_MS);
    return () => {
      if (refreshTimer.current) {
        clearInterval(refreshTimer.current);
        refreshTimer.current = null;
      }
    };
  }, [state.kind, router]);

  const hasPermission = useCallback(
    (node: string): boolean => {
      if (state.kind === "api-token") return true;
      if (state.kind !== "user") return false;
      if (state.user.isAdmin) return true;
      return state.user.permissions.some((p) => matchesPermission(p, node));
    },
    [state]
  );

  const refresh = useCallback(async () => {
    if (state.kind !== "user") return;
    const user = await fetchMe();
    if (user) setState({ kind: "user", user });
  }, [state.kind]);

  const logout = useCallback(async () => {
    if (state.kind === "user") {
      try {
        await apiFetch("/api/auth/logout", { method: "POST", silent: true });
      } catch {
        // Ignore — we're clearing creds locally regardless.
      }
    }
    clearCredentials();
    setState({ kind: "anonymous" });
    router.replace("/login");
  }, [state.kind, router]);

  if (state.kind === "loading") return null;
  if (state.kind === "anonymous") return null;

  const value: AuthContextValue = {
    state,
    authenticated: state.kind === "api-token" || state.kind === "user",
    user: state.kind === "user" ? state.user : null,
    hasPermission,
    refresh,
    logout,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
