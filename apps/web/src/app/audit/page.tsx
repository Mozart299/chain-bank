"use client";

import { CircleCheck, CircleX, Copy, Download, ShieldCheck } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";

import { FormError } from "@/components/form-error";
import { useSession } from "@/components/session-provider";
import { StepUpCard, stepUpActive } from "@/components/step-up-card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import type { AuditBatch, BatchVerification, ChainStatus } from "@/lib/banking";
import { api, errorMessage } from "@/lib/client";

const ROLES = ["AUDITOR", "COMPLIANCE_REVIEWER", "SYSTEM_ADMIN"];

/** Audit trail: hash-chain status, batches anchored on-chain, verification and exports (ADR 0007). */
export default function AuditPage() {
  const { me, status } = useSession();
  const router = useRouter();
  const allowed = me?.roles.some((r) => ROLES.includes(r)) ?? false;
  const isAdmin = me?.roles.includes("SYSTEM_ADMIN") ?? false;
  const [chainOk, setChainOk] = useState<{ valid: boolean; eventsChecked: number } | null>(null);
  const [batches, setBatches] = useState<AuditBatch[] | null>(null);
  const [anchor, setAnchor] = useState<string | null>(null);
  const [results, setResults] = useState<Record<number, BatchVerification>>({});
  const [error, setError] = useState<string | null>(null);
  const [sealing, setSealing] = useState(false);

  const load = useCallback(async () => {
    try {
      const [c, b, s] = await Promise.all([
        api<{ valid: boolean; eventsChecked: number }>("/api/audit/chain/verify"),
        api<AuditBatch[]>("/api/audit/batches"),
        api<ChainStatus>("/api/admin/chain").catch(() => null),
      ]);
      setChainOk(c);
      setBatches(b);
      setAnchor(s?.auditAnchor ?? null);
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    if (status === "anonymous") router.replace("/login");
    if (!allowed) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch from the API
    void load();
    const timer = setInterval(() => void load(), 5000);
    return () => clearInterval(timer);
  }, [status, allowed, router, load]);

  if (status !== "authenticated") {
    return <main className="text-muted-foreground mx-auto w-full max-w-3xl px-4 py-12">Loading…</main>;
  }
  if (!allowed) {
    return (
      <main className="mx-auto w-full max-w-3xl px-4 py-12">
        <Card>
          <CardHeader>
            <CardTitle>Auditors only</CardTitle>
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

  async function verify(id: number) {
    try {
      const v = await api<BatchVerification>(`/api/audit/${id}/verify`);
      setResults((r) => ({ ...r, [id]: v }));
    } catch (e) {
      toast.error(errorMessage(e));
    }
  }

  async function seal() {
    setSealing(true);
    try {
      const r = await api<{ batchId: number }>("/api/audit/batches/seal", { method: "POST" });
      toast.success(`Batch ${r.batchId} sealed. It is anchored on-chain within a few seconds.`);
      await load();
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setSealing(false);
    }
  }

  const command = (id: number) =>
    `node tools/audit-verifier/verify.mjs chainbank-audit-batch-${id}.json --rpc http://127.0.0.1:8545${anchor ? ` --contract ${anchor}` : ""}`;

  return (
    <main className="mx-auto w-full max-w-3xl space-y-6 px-4 py-8">
      <div className="space-y-1">
        <h1 className="text-2xl font-semibold">Audit trail</h1>
        <p className="text-muted-foreground text-sm">
          Every security and money event is hash-chained. Batches of events are sealed into a Merkle root and
          committed to the chain, so anyone can check that history was not rewritten.
        </p>
      </div>
      <FormError message={error} />

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <ShieldCheck className="size-5" aria-hidden /> Hash chain
            {chainOk && (
              <Badge variant={chainOk.valid ? "secondary" : "destructive"}>{chainOk.valid ? "Intact" : "Broken"}</Badge>
            )}
          </CardTitle>
          <CardDescription>
            {chainOk ? `${chainOk.eventsChecked} events re-hashed from the start of the log.` : "Checking…"}
            {anchor && (
              <>
                {" "}
                Published anchor contract: <span className="font-mono break-all">{anchor}</span>
              </>
            )}
          </CardDescription>
        </CardHeader>
        {isAdmin && (
          <CardContent className="grid gap-2">
            <div>
              <Button onClick={seal} disabled={sealing || !stepUpActive(me?.stepUpValidUntil)}>
                {sealing ? "Sealing…" : "Seal checkpoint now"}
              </Button>
            </div>
            <p className="text-muted-foreground text-xs">
              Batches seal automatically every 100 events or 10 minutes. Sealing now requires step-up.
            </p>
          </CardContent>
        )}
      </Card>

      {isAdmin && !stepUpActive(me?.stepUpValidUntil) && (
        <StepUpCard description="Sealing a checkpoint requires a fresh authenticator code." />
      )}

      <Card>
        <CardHeader>
          <CardTitle>Committed batches</CardTitle>
          <CardDescription>
            Verify a batch here, or download it and check it yourself with the independent verifier.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {!batches ? (
            <p className="text-muted-foreground text-sm">Loading…</p>
          ) : batches.length === 0 ? (
            <p className="text-muted-foreground text-sm">No batches sealed yet.</p>
          ) : (
            <ul className="divide-y">
              {batches.map((b) => {
                const v = results[b.id];
                return (
                  <li key={b.id} className="grid gap-2 py-4 text-sm">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">Batch {b.id}</span>
                      <Badge variant={b.status === "COMMITTED" ? "secondary" : b.status === "FAILED" ? "destructive" : "outline"}>
                        {b.status}
                      </Badge>
                      <span className="text-muted-foreground">
                        events {b.firstEventId}–{b.lastEventId} ({b.eventCount})
                        {b.blockNumber ? ` · block ${b.blockNumber}` : ""}
                      </span>
                    </div>
                    <p className="text-muted-foreground font-mono text-xs break-all">root {b.merkleRoot}</p>
                    {b.txHash && <p className="text-muted-foreground font-mono text-xs break-all">tx {b.txHash}</p>}
                    <div className="flex flex-wrap gap-2">
                      <Button size="sm" variant="outline" onClick={() => verify(b.id)} disabled={b.status !== "COMMITTED"}>
                        Verify
                      </Button>
                      <Button size="sm" variant="outline" asChild>
                        <a href={`/api/audit/batches/${b.id}/export`} download={`chainbank-audit-batch-${b.id}.json`}>
                          <Download aria-hidden /> Download export
                        </a>
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => {
                          void navigator.clipboard?.writeText(command(b.id));
                          toast.success("Verifier command copied");
                        }}
                      >
                        <Copy aria-hidden /> Copy verifier command
                      </Button>
                    </div>
                    {v && (
                      <ul className="bg-muted/50 grid gap-1 rounded-md p-3" aria-label={`Verification of batch ${b.id}`}>
                        {v.checks.map((c) => (
                          <li key={c.name} className="flex items-start gap-2">
                            {c.ok ? (
                              <CircleCheck className="mt-0.5 size-4 shrink-0 text-emerald-600" aria-label="passed" />
                            ) : (
                              <CircleX className="mt-0.5 size-4 shrink-0 text-red-600" aria-label="failed" />
                            )}
                            <span>
                              <span className="font-medium">{c.name}</span>{" "}
                              <span className="text-muted-foreground break-words">{c.detail}</span>
                            </span>
                          </li>
                        ))}
                        <li className="pt-1 font-medium">{v.valid ? "Batch verified." : "Verification failed."}</li>
                      </ul>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </CardContent>
      </Card>
    </main>
  );
}
