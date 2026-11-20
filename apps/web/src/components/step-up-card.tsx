"use client";

import { ShieldCheck } from "lucide-react";
import { useState, type SubmitEvent } from "react";
import { toast } from "sonner";

import { FormError } from "@/components/form-error";
import { useSession } from "@/components/session-provider";
import { TotpField } from "@/components/totp-field";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { api, errorMessage } from "@/lib/client";

export function stepUpActive(until: string | null | undefined): boolean {
  return !!until && new Date(until) > new Date();
}

export function StepUpCard({ description }: { description: string }) {
  const { me, refresh } = useSession();
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const active = stepUpActive(me?.stepUpValidUntil);

  async function onSubmit(e: SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    setPending(true);
    setError(null);
    try {
      await api("/api/auth/step-up", { method: "POST", body: { code: new FormData(form).get("code") } });
      form.reset();
      toast.success("Verified. Sensitive actions are unlocked for 5 minutes.");
      await refresh();
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
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        {active && me?.stepUpValidUntil ? (
          <Alert>
            <ShieldCheck aria-hidden />
            <AlertDescription>Verified until {new Date(me.stepUpValidUntil).toLocaleTimeString()}.</AlertDescription>
          </Alert>
        ) : (
          <form onSubmit={onSubmit} className="flex flex-wrap items-end gap-4" noValidate>
            <TotpField description={null} />
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
