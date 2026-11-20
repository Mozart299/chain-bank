"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { FormError } from "@/components/form-error";
import { useSession } from "@/components/session-provider";
import { StepUpCard, stepUpActive } from "@/components/step-up-card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { api, errorMessage, setCsrfToken } from "@/lib/client";

import { WalletPanel } from "../wallet-panel";

export default function SecurityPage() {
  const { me } = useSession();
  if (!me) return null;
  const active = stepUpActive(me.stepUpValidUntil);

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <h1 className="text-2xl font-semibold">{me.displayName}</h1>
        <p className="text-muted-foreground text-sm">{me.email}</p>
        <div className="flex flex-wrap gap-2">
          {me.roles.map((r) => (
            <Badge key={r} variant="secondary">
              {r}
            </Badge>
          ))}
        </div>
      </header>

      <StepUpCard description="Sensitive actions (adding another wallet, unlinking, revoking sessions, operator reviews) need a fresh authenticator code." />

      {me.roles.includes("CUSTOMER") && <WalletPanel stepUpActive={active} />}

      <SessionsCard stepUpActive={active} />
    </div>
  );
}

function SessionsCard({ stepUpActive }: { stepUpActive: boolean }) {
  const router = useRouter();
  const { refresh } = useSession();
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function revokeAll() {
    setPending(true);
    setError(null);
    try {
      await api("/api/auth/sessions/revoke-all", { method: "POST" });
      setCsrfToken(null);
      await refresh();
      router.replace("/login");
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
