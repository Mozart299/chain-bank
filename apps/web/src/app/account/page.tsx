"use client";

import { ShieldCheck } from "lucide-react";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState, type SubmitEvent } from "react";
import { toast } from "sonner";

import { FormError } from "@/components/form-error";
import { TotpField } from "@/components/totp-field";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { api, errorMessage, loadMe, setCsrfToken, type Me } from "@/lib/client";

import { WalletPanel } from "./wallet-panel";

export default function AccountPage() {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const current = await loadMe();
      if (!current) {
        router.replace("/login");
        return;
      }
      setMe(current);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setLoading(false);
    }
  }, [router]);

  useEffect(() => {
    // Loading the session is an external-system sync, the intended use of an effect.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
  }, [refresh]);

  async function logout() {
    await api("/api/auth/logout", { method: "POST" }).catch(() => undefined);
    setCsrfToken(null);
    router.replace("/login");
  }

  if (loading) {
    return (
      <main className="text-muted-foreground mx-auto w-full max-w-2xl px-4 py-12" aria-live="polite">
        Loading your account…
      </main>
    );
  }
  if (!me) {
    return (
      <main className="mx-auto w-full max-w-2xl px-4 py-12">
        <FormError message={error} />
      </main>
    );
  }

  const stepUpActive = me.stepUpValidUntil !== null && new Date(me.stepUpValidUntil) > new Date();

  return (
    <main className="mx-auto w-full max-w-2xl space-y-6 px-4 py-10">
      <header className="flex items-start justify-between gap-4">
        <div className="space-y-2">
          <h1 className="text-2xl font-semibold">{me.displayName}</h1>
          <p className="text-muted-foreground text-sm">{me.email}</p>
          <div className="flex flex-wrap gap-2">
            {me.roles.map((r) => (
              <Badge key={r} variant="secondary">
                {r}
              </Badge>
            ))}
          </div>
        </div>
        <Button variant="outline" onClick={logout}>
          Sign out
        </Button>
      </header>

      <StepUpCard active={stepUpActive} until={me.stepUpValidUntil} onDone={refresh} />

      {me.roles.includes("CUSTOMER") && <WalletPanel stepUpActive={stepUpActive} />}

      <SessionsCard stepUpActive={stepUpActive} onRevoked={() => router.replace("/login")} />
    </main>
  );
}

function StepUpCard({ active, until, onDone }: { active: boolean; until: string | null; onDone: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function onSubmit(e: SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    setPending(true);
    setError(null);
    try {
      await api("/api/auth/step-up", { method: "POST", body: { code: new FormData(form).get("code") } });
      form.reset();
      toast.success("Verified. Sensitive actions are unlocked for 5 minutes.");
      onDone();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Step-up verification</CardTitle>
        <CardDescription>
          Sensitive actions (adding another wallet, unlinking, revoking sessions) need a fresh authenticator code.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        {active && until ? (
          <Alert>
            <ShieldCheck aria-hidden />
            <AlertDescription>Verified until {new Date(until).toLocaleTimeString()}.</AlertDescription>
          </Alert>
        ) : (
          <form onSubmit={onSubmit} className="flex flex-wrap items-end gap-4" noValidate>
            <TotpField description="Unlocks sensitive actions for 5 minutes" />
            <Button type="submit" disabled={pending} aria-busy={pending || undefined}>
              {pending ? "Verifying…" : "Verify"}
            </Button>
          </form>
        )}
        <FormError message={error} />
      </CardContent>
    </Card>
  );
}

function SessionsCard({ stepUpActive, onRevoked }: { stepUpActive: boolean; onRevoked: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function revokeAll() {
    setPending(true);
    setError(null);
    try {
      await api("/api/auth/sessions/revoke-all", { method: "POST" });
      setCsrfToken(null);
      onRevoked();
    } catch (err) {
      setError(errorMessage(err));
      setPending(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Sessions</CardTitle>
        <CardDescription>Sign out everywhere, including other browsers and devices.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3">
        <div>
          <Button
            variant="destructive"
            onClick={revokeAll}
            disabled={!stepUpActive || pending}
            aria-busy={pending || undefined}
          >
            {pending ? "Revoking…" : "Revoke all sessions"}
          </Button>
        </div>
        {!stepUpActive && <p className="text-muted-foreground text-xs">Requires step-up verification.</p>}
        <FormError message={error} />
      </CardContent>
    </Card>
  );
}
