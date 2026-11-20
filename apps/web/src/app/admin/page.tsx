"use client";

import { Pause, Play } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";

import { FormError } from "@/components/form-error";
import { useSession } from "@/components/session-provider";
import { SettlementOverview } from "@/components/settlement-overview";
import { StepUpCard, stepUpActive } from "@/components/step-up-card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import type { ChainStatus } from "@/lib/banking";
import { api, errorMessage } from "@/lib/client";

/** System administration: settlement chain status and the emergency pause (spec section 8.4). */
export default function AdminPage() {
  const { me, status } = useSession();
  const router = useRouter();
  const [chain, setChain] = useState<ChainStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const isAdmin = me?.roles.includes("SYSTEM_ADMIN") ?? false;

  const load = useCallback(async () => {
    try {
      setChain(await api<ChainStatus>("/api/admin/chain"));
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    if (status === "anonymous") router.replace("/login");
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch from the API
    if (isAdmin) void load();
  }, [status, isAdmin, router, load]);

  if (status !== "authenticated") {
    return <main className="text-muted-foreground mx-auto w-full max-w-3xl px-4 py-12">Loading…</main>;
  }
  if (!isAdmin) {
    return (
      <main className="mx-auto w-full max-w-3xl px-4 py-12">
        <Card>
          <CardHeader>
            <CardTitle>Administrators only</CardTitle>
            <CardDescription>
              <Link href="/account" className="text-foreground underline underline-offset-4">
                Back to your account
              </Link>
            </CardDescription>
          </CardHeader>
        </Card>
      </main>
    );
  }

  const canAct = stepUpActive(me?.stepUpValidUntil);

  async function toggle(pause: boolean) {
    setPending(true);
    setError(null);
    try {
      setChain(await api<ChainStatus>(`/api/admin/chain/${pause ? "pause" : "unpause"}`, { method: "POST" }));
      toast.success(pause ? "Settlement paused. Transfers keep posting and queue for settlement." : "Settlement resumed.");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  }

  return (
    <main className="mx-auto w-full max-w-3xl space-y-6 px-4 py-8">
      <div className="space-y-1">
        <h1 className="text-2xl font-semibold">Administration</h1>
        <p className="text-muted-foreground text-sm">Settlement chain status and emergency controls.</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            Settlement chain
            {chain && <Badge variant={chain.paused ? "destructive" : "secondary"}>{chain.paused ? "Paused" : "Running"}</Badge>}
          </CardTitle>
          <CardDescription>
            Pausing stops all on-chain settlement immediately. Customers can still transfer; interbank settlements
            queue and resume when unpaused.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 text-sm">
          {chain && (
            <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2">
              <dt className="text-muted-foreground">Chain</dt>
              <dd>
                {chain.chainId} · block {chain.latestBlock} · finality {chain.confirmations} confirmations
              </dd>
              <dt className="text-muted-foreground">Contract</dt>
              <dd className="truncate font-mono text-xs">{chain.contract ?? "not deployed"}</dd>
              <dt className="text-muted-foreground">Backlog</dt>
              <dd>
                {chain.pendingOutbox} waiting to submit · {chain.openTransactions} awaiting confirmation
              </dd>
            </dl>
          )}
          <div>
            {chain?.paused ? (
              <Button onClick={() => toggle(false)} disabled={!canAct || pending}>
                <Play aria-hidden /> Resume settlement
              </Button>
            ) : (
              <Button variant="destructive" onClick={() => toggle(true)} disabled={!canAct || pending || !chain}>
                <Pause aria-hidden /> Emergency pause
              </Button>
            )}
          </div>
          {!canAct && <p className="text-muted-foreground text-xs">Requires step-up verification.</p>}
          <FormError message={error} />
        </CardContent>
      </Card>

      {!canAct && <StepUpCard description="Pausing or resuming settlement requires a fresh authenticator code." />}

      <SettlementOverview />
    </main>
  );
}
