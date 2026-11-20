"use client";

import { CircleAlert, CircleCheck, RotateCw } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";

import { FormError } from "@/components/form-error";
import { useSession } from "@/components/session-provider";
import { stepUpActive } from "@/components/step-up-card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { formatMoney, type Position, type ReconciliationException } from "@/lib/banking";
import { api, errorMessage } from "@/lib/client";

/**
 * Off-chain settlement ledgers vs on-chain token balances, and the reconciliation exception queue.
 * Refreshes every 5 seconds.
 */
export function SettlementOverview() {
  const { me } = useSession();
  const canAct =
    (me?.roles.includes("BANK_OPERATOR") || me?.roles.includes("SYSTEM_ADMIN")) && stepUpActive(me?.stepUpValidUntil);
  const [positions, setPositions] = useState<Position[] | null>(null);
  const [exceptions, setExceptions] = useState<ReconciliationException[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [p, e] = await Promise.all([
        api<Position[]>("/api/settlement/positions"),
        api<ReconciliationException[]>("/api/reconciliation/exceptions"),
      ]);
      setPositions(p);
      setExceptions(e);
      setError(null);
    } catch (err) {
      setError(errorMessage(err));
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch from the API
    void load();
    const timer = setInterval(() => void load(), 5000);
    return () => clearInterval(timer);
  }, [load]);

  const open = exceptions?.filter((e) => e.status !== "RESOLVED") ?? [];

  return (
    <div className="space-y-6">
      <FormError message={error} />
      <Card>
        <CardHeader>
          <CardTitle>Settlement positions</CardTitle>
          <CardDescription>
            Each bank&apos;s settlement account in the ledger, compared with its token balance on-chain
            {positions?.[0] ? ` (final block ${positions[0].asOfBlock})` : ""}. Transfers posted but not yet settled
            explain any timing difference.
          </CardDescription>
        </CardHeader>
        <CardContent className="overflow-x-auto">
          {!positions ? (
            <p className="text-muted-foreground text-sm">Loading…</p>
          ) : (
            <table className="w-full min-w-[34rem] text-left text-sm">
              <caption className="sr-only">Settlement positions per bank</caption>
              <thead className="text-muted-foreground">
                <tr className="border-b">
                  <th scope="col" className="py-2 font-medium">Bank</th>
                  <th scope="col" className="text-right font-medium">Ledger</th>
                  <th scope="col" className="text-right font-medium">In flight</th>
                  <th scope="col" className="text-right font-medium">On-chain</th>
                  <th scope="col" className="pl-4 font-medium">Status</th>
                </tr>
              </thead>
              <tbody>
                {positions.map((p) => (
                  <tr key={p.institution} className="border-b last:border-0">
                    <td className="py-2">
                      <span className="font-medium">{p.institution}</span>{" "}
                      <span className="text-muted-foreground font-mono text-xs">{p.pseudonym}</span>
                    </td>
                    <td className="text-right tabular-nums">{formatMoney(p.offChainLedger)}</td>
                    <td className="text-right tabular-nums">
                      {p.outgoingUnsettled - p.incomingUnsettled === 0
                        ? "–"
                        : formatMoney(p.outgoingUnsettled - p.incomingUnsettled)}
                    </td>
                    <td className="text-right tabular-nums">{p.onChain === null ? "unavailable" : formatMoney(p.onChain)}</td>
                    <td className="pl-4">
                      {p.matched ? (
                        <span className="inline-flex items-center gap-1 text-emerald-700 dark:text-emerald-400">
                          <CircleCheck className="size-4" aria-hidden /> Matched
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-red-700 dark:text-red-400">
                          <CircleAlert className="size-4" aria-hidden /> Mismatch
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            Reconciliation exceptions
            {open.length > 0 && <Badge variant="destructive">{open.length} open</Badge>}
          </CardTitle>
          <CardDescription>
            On-chain events and ledger entries that did not line up.
            {open.length > 0 && !canAct && " Operators can resolve or retry them after a step-up verification."}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {!exceptions ? (
            <p className="text-muted-foreground text-sm">Loading…</p>
          ) : exceptions.length === 0 ? (
            <p className="text-muted-foreground text-sm">No exceptions. Every settlement matched the ledger.</p>
          ) : (
            <ul className="divide-y">
              {exceptions.slice(0, 20).map((e) => (
                <li key={e.id} className="grid gap-1 py-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant={e.status === "RESOLVED" ? "secondary" : "destructive"}>{e.status}</Badge>
                    <span className="font-medium">{e.kind.replaceAll("_", " ").toLowerCase()}</span>
                    <span className="text-muted-foreground text-xs">{new Date(e.createdAt).toLocaleString()}</span>
                  </div>
                  {e.note && <p className="text-muted-foreground break-words">{e.note}</p>}
                  {e.resolutionNote && (
                    <p className="text-muted-foreground break-words">Resolution: {e.resolutionNote}</p>
                  )}
                  {e.status !== "RESOLVED" && canAct && <ExceptionActions exception={e} onDone={load} />}
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function ExceptionActions({ exception, onDone }: { exception: ReconciliationException; onDone: () => Promise<void> }) {
  const [note, setNote] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState(false);

  async function retry() {
    setPending(true);
    try {
      await api(`/api/reconciliation/exceptions/${exception.id}/retry`, { method: "POST" });
      toast.success("Settlement queued again. The exception closes once it is final.");
      await onDone();
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setPending(false);
    }
  }

  async function resolve() {
    setPending(true);
    setError(null);
    try {
      await api(`/api/reconciliation/exceptions/${exception.id}/resolve`, { method: "POST", body: { note } });
      toast.success("Exception resolved.");
      setOpen(false);
      await onDone();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="flex flex-wrap gap-2 pt-1">
      {exception.retryable && (
        <Button size="sm" variant="outline" onClick={retry} disabled={pending}>
          <RotateCw aria-hidden /> Retry settlement
        </Button>
      )}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogTrigger asChild>
          <Button size="sm" variant="outline">
            Resolve…
          </Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Resolve exception</DialogTitle>
            <DialogDescription>
              Record what you found and how it was handled. The note is kept in the audit log.
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-2">
            <Label htmlFor={`note-${exception.id}`}>Resolution note</Label>
            <Textarea
              id={`note-${exception.id}`}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              minLength={5}
              maxLength={500}
              rows={4}
            />
          </div>
          <FormError message={error} />
          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">Cancel</Button>
            </DialogClose>
            <Button onClick={resolve} disabled={pending || note.trim().length < 5}>
              {pending ? "Saving…" : "Resolve"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
