"use client";

import { useRouter } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

import { api, loadMe, setCsrfToken, type Me } from "@/lib/client";

type SessionState = {
  me: Me | null;
  status: "loading" | "authenticated" | "anonymous";
  /** Re-reads /api/auth/me, e.g. after login or step-up. */
  refresh: () => Promise<Me | null>;
  signOut: () => Promise<void>;
};

const SessionContext = createContext<SessionState | null>(null);

/** Loads the signed-in user once for the whole app, so the header and pages agree on auth state. */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [status, setStatus] = useState<SessionState["status"]>("loading");

  const refresh = useCallback(async () => {
    try {
      const current = await loadMe();
      setMe(current);
      setStatus(current ? "authenticated" : "anonymous");
      return current;
    } catch {
      setMe(null);
      setStatus("anonymous");
      return null;
    }
  }, []);

  const signOut = useCallback(async () => {
    await api("/api/auth/logout", { method: "POST" }).catch(() => undefined);
    setCsrfToken(null);
    // Full page load rather than client navigation: discards all in-memory state (CSRF token, wallet
    // connection, cached data) and avoids racing pages that redirect anonymous users to /login.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- intentional hard reload
    window.location.assign("/");
  }, []);

  useEffect(() => {
    // Syncing with the API session is an external-system effect.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
  }, [refresh]);

  const value = useMemo(() => ({ me, status, refresh, signOut }), [me, status, refresh, signOut]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionState {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error("useSession must be used inside <SessionProvider>");
  return ctx;
}

/** Sends signed-in users away from pages meant for visitors (login, register). */
export function useRedirectIfAuthenticated(to = "/account") {
  const { status } = useSession();
  const router = useRouter();
  useEffect(() => {
    if (status === "authenticated") router.replace(to);
  }, [status, router, to]);
}
