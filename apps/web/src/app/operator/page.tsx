"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";

import { FormError } from "@/components/form-error";
import { useSession } from "@/components/session-provider";
import { StepUpCard, stepUpActive } from "@/components/step-up-card";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { formatMoney, type Transfer } from "@/lib/banking";
import { api, errorMessage } from "@/lib/client";

/** Bank-operator queue: flagged interbank transfers from this operator's bank (ADR 0005). */
export default function OperatorPage() {
  const { me, status } = useSession();
  const router = useRouter();
  const [queue, setQueue] = useState<Transfer[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const isOperator = me?.roles.includes("BANK_OPERATOR") ?? false;

  const load = useCallback(async () => {
    try {
      setQueue(await api<Transfer[]>("/api/operator/reviews"));
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    if (status === "anonymous") router.replace("/login");
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch from the API
    if (isOperator) void load();
  }, [status, isOperator, router, load]);

  if (status !== "authenticated") {
    return (
      <main className="text-muted-foreground mx-auto w-full max-w-3xl px-4 py-12" aria-live="polite">
        Loading…
      </main>
    );
  }
  if (!isOperator) {
    return (
      <main className="mx-auto w-full max-w-3xl px-4 py-12">
        <Card>
          <CardHeader>
            <CardTitle>Operators only</CardTitle>
            <CardDescription>
              This queue is for bank operators.{" "}
              <Link href="/account" className="text-foreground underline underline-offset-4">
                Back to your account
              </Link>
            </CardDescription>
          </CardHeader>
        </Card>
      </main>
    );
  }

  const canDecide = stepUpActive(me?.stepUpValidUntil);

  return (
    <main className="mx-auto w-full max-w-3xl space-y-6 px-4 py-8">
      <div className="space-y-1">
        <h1 className="text-2xl font-semibold">Review queue</h1>
        <p className="text-muted-foreground text-sm">
          Interbank transfers over {formatMoney(2_000_000)} leaving your bank wait here for a decision.
        </p>
      </div>

      {!canDecide && <StepUpCard description="Approving or rejecting customer transfers requires a fresh authenticator code." />}

      <FormError message={error} />

      {queue === null ? (
        <p className="text-muted-foreground">Loading queue…</p>
      ) : queue.length === 0 ? (
        <Card>
          <CardContent className="text-muted-foreground pt-6 text-sm">Nothing to review.</CardContent>
        </Card>
      ) : (
        queue.map((t) => <ReviewCard key={t.id} transfer={t} enabled={canDecide} onDone={load} />)
      )}
    </main>
  );
}

function ReviewCard({ transfer: t, enabled, onDone }: { transfer: Transfer; enabled: boolean; onDone: () => Promise<void> }) {
  const [pending, setPending] = useState<"APPROVE" | "REJECT" | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function decide(decision: "APPROVE" | "REJECT") {
    setPending(decision);
    setError(null);
    try {
      await api(`/api/operator/transfers/${t.id}/review`, { method: "POST", body: { decision } });
      toast.success(decision === "APPROVE" ? "Transfer released." : "Transfer rejected and funds returned.");
      await onDone();
    } catch (err) {
      setError(errorMessage(err));
      setPending(null);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="tabular-nums">{formatMoney(t.amount, t.currency)}</CardTitle>
        <CardDescription>
          {t.source.holder} ({t.source.accountNumber}) → {t.destination.holder} ({t.destination.accountNumber},{" "}
          {t.destination.bank})
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3 text-sm">
        <p className="text-muted-foreground">
          Requested {new Date(t.createdAt).toLocaleString()}
          {t.reference ? ` · “${t.reference}”` : ""} · customer approval:{" "}
          {t.requiresStepUp ? (t.stepUpApproved ? "given" : "pending") : "not required"} · expires{" "}
          {new Date(t.expiresAt).toLocaleTimeString()}
        </p>
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => decide("APPROVE")} disabled={!enabled || pending !== null}>
            {pending === "APPROVE" ? "Approving…" : "Approve"}
          </Button>
          <Button variant="destructive" onClick={() => decide("REJECT")} disabled={!enabled || pending !== null}>
            {pending === "REJECT" ? "Rejecting…" : "Reject"}
          </Button>
        </div>
        <FormError message={error} />
      </CardContent>
    </Card>
  );
}
