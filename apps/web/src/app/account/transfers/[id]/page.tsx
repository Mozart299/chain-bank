"use client";

import { ArrowLeft, Check, Circle, Copy, X } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState, type SubmitEvent } from "react";
import { toast } from "sonner";

import { FormError } from "@/components/form-error";
import { TotpField } from "@/components/totp-field";
import { TransferStatus } from "@/components/transfer-status";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { awaitingCustomer, awaitingReview, formatMoney, REASONS, settling, STATUS, type Transfer } from "@/lib/banking";
import { api, errorMessage } from "@/lib/client";

export default function TransferPage() {
  const { id } = useParams<{ id: string }>();
  const [transfer, setTransfer] = useState<Transfer | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setTransfer(await api<Transfer>(`/api/transfers/${id}`));
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [id]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch from the API
    void load();
  }, [load]);

  // Follow on-chain settlement live: poll every 2 s until the transfer reaches a final state.
  const live = transfer !== null && settling(transfer);
  useEffect(() => {
    if (!live) return;
    const timer = setInterval(() => void load(), 2000);
    return () => clearInterval(timer);
  }, [live, load]);

  if (error && !transfer) return <FormError message={error} />;
  if (!transfer) {
    return (
      <p className="text-muted-foreground" aria-live="polite">
        Loading transfer…
      </p>
    );
  }

  const t = transfer;
  const outgoing = t.direction === "OUTGOING";

  return (
    <div className="space-y-6">
      <Link href="/account" className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1 text-sm">
        <ArrowLeft className="size-4" aria-hidden /> Back to overview
      </Link>

      <Card>
        <CardHeader>
          <CardDescription>{outgoing ? "You sent" : "You received"}</CardDescription>
          <CardTitle className="text-3xl tabular-nums">{formatMoney(t.amount, t.currency)}</CardTitle>
          <div>
            <TransferStatus state={t.state} />
          </div>
        </CardHeader>
        <CardContent className="grid gap-4 text-sm">
          <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2">
            <dt className="text-muted-foreground">From</dt>
            <dd>
              {t.source.holder} · <span className="font-mono">{t.source.accountNumber}</span> · {t.source.bank}
            </dd>
            <dt className="text-muted-foreground">To</dt>
            <dd>
              {t.destination.holder} · <span className="font-mono">{t.destination.accountNumber}</span> ·{" "}
              {t.destination.bank}
            </dd>
            {t.reference && (
              <>
                <dt className="text-muted-foreground">Reference</dt>
                <dd>{t.reference}</dd>
              </>
            )}
            <dt className="text-muted-foreground">Type</dt>
            <dd>{t.crossBank ? "Interbank (settles on-chain)" : "Same bank (book transfer)"}</dd>
          </dl>
          {t.failureReason && (
            <p className="text-muted-foreground">{REASONS[t.failureReason] ?? t.failureReason}</p>
          )}
          {t.crossBank && ["APPROVED", "SUBMITTED"].includes(t.state) && (
            <p className="text-muted-foreground">
              The money has left your account and arrived in the payee&apos;s. The two banks are now settling with
              each other using fictional settlement tokens on the local chain.
            </p>
          )}
        </CardContent>
      </Card>

      {t.crossBank && t.state !== "VALIDATED" && t.state !== "REJECTED" && t.state !== "CANCELLED" && (
        <SettlementCard transfer={t} />
      )}

      {awaitingCustomer(t) && <ApproveCard transfer={t} onDone={load} />}

      {awaitingReview(t) && (
        <Card className="border-amber-500/50">
          <CardHeader>
            <CardTitle>Bank review</CardTitle>
            <CardDescription>
              Interbank transfers over {formatMoney(2_000_000)} are checked by an operator at{" "}
              {t.source.bank} before they are released.
            </CardDescription>
          </CardHeader>
        </Card>
      )}

      {outgoing && t.state === "VALIDATED" && <CancelCard transfer={t} onDone={load} />}

      <Card>
        <CardHeader>
          <CardTitle>Timeline</CardTitle>
        </CardHeader>
        <CardContent>
          <ol className="space-y-3">
            {t.events.map((e, i) => {
              const terminalBad = ["REJECTED", "CANCELLED", "FAILED"].includes(e.toState);
              const Icon = terminalBad ? X : i === t.events.length - 1 && e.toState === "VALIDATED" ? Circle : Check;
              return (
                <li key={i} className="flex items-start gap-3 text-sm">
                  <Icon className="mt-0.5 size-4 shrink-0" aria-hidden />
                  <div>
                    <p className="font-medium">{STATUS[e.toState as Transfer["state"]]?.label ?? e.toState}</p>
                    <p className="text-muted-foreground text-xs break-all">
                      {new Date(e.at).toLocaleString()}
                      {e.reason ? ` · ${REASONS[e.reason] ?? e.reason}` : ""}
                    </p>
                  </div>
                </li>
              );
            })}
          </ol>
        </CardContent>
      </Card>
    </div>
  );
}

function SettlementCard({ transfer: t }: { transfer: Transfer }) {
  const s = t.settlement;
  const done = t.state === "CONFIRMED" || t.state === "RECONCILED";
  const shown = s ? Math.min(s.confirmations, s.required) : 0;
  const pct = s ? Math.round((shown / s.required) * 100) : 0;
  return (
    <Card>
      <CardHeader>
        <CardTitle>On-chain settlement</CardTitle>
        <CardDescription>
          {!s
            ? "Queued: the bank's gateway will submit it to the local chain in a moment."
            : t.state === "RECONCILED"
              ? "Final and matched against the bank's ledger."
              : done
                ? "Final. Waiting for the reconciliation check."
                : s.status === "REORGED"
                  ? "The block was replaced by a chain reorganisation; re-confirming."
                  : s.status === "REVERTED"
                    ? "The transaction was rejected on-chain. The bank is investigating."
                    : `Waiting for ${s.required} confirmations before it counts as final.`}
        </CardDescription>
      </CardHeader>
      {s && (
        <CardContent className="grid gap-4 text-sm">
          <div className="grid gap-2">
            <div className="flex justify-between text-xs">
              <span className="text-muted-foreground">Confirmations</span>
              <span className="tabular-nums">
                {shown} of {s.required}
              </span>
            </div>
            <div
              role="progressbar"
              aria-label="Settlement confirmations"
              aria-valuemin={0}
              aria-valuemax={s.required}
              aria-valuenow={shown}
              className="bg-muted h-2 overflow-hidden rounded-full"
            >
              <div className="bg-primary h-full transition-all" style={{ width: `${pct}%` }} />
            </div>
          </div>
          <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2">
            <dt className="text-muted-foreground">Transaction</dt>
            <dd className="flex min-w-0 items-center gap-1">
              <span className="truncate font-mono text-xs" title={s.txHash}>
                {s.txHash}
              </span>
              <Button
                variant="ghost"
                size="icon-xs"
                aria-label="Copy transaction hash"
                onClick={() => {
                  void navigator.clipboard?.writeText(s.txHash);
                  toast.success("Transaction hash copied");
                }}
              >
                <Copy aria-hidden />
              </Button>
            </dd>
            <dt className="text-muted-foreground">Block</dt>
            <dd className="tabular-nums">{s.blockNumber ?? "pending"}</dd>
            <dt className="text-muted-foreground">Status</dt>
            <dd>{s.status}</dd>
          </dl>
        </CardContent>
      )}
    </Card>
  );
}

function ApproveCard({ transfer, onDone }: { transfer: Transfer; onDone: () => Promise<void> }) {
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const reason =
    transfer.amount > 1_000_000
      ? `It is over ${formatMoney(1_000_000)}.`
      : "It is your first transfer to this payee.";

  async function onSubmit(e: SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    setPending(true);
    setError(null);
    try {
      await api(`/api/transfers/${transfer.id}/approve`, {
        method: "POST",
        body: { code: new FormData(e.currentTarget).get("code") },
      });
      toast.success("Transfer approved.");
      await onDone();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <Card className="border-amber-500/50">
      <CardHeader>
        <CardTitle>Approve this transfer</CardTitle>
        <CardDescription>
          {reason} Enter a code from your authenticator app. Approve before{" "}
          {new Date(transfer.expiresAt).toLocaleTimeString()} or the funds are released.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        <form onSubmit={onSubmit} className="flex flex-wrap items-end gap-4" noValidate>
          <TotpField description={null} />
          <Button type="submit" disabled={pending} aria-busy={pending || undefined}>
            {pending ? "Approving…" : `Approve ${formatMoney(transfer.amount)}`}
          </Button>
        </form>
        <FormError message={error} />
      </CardContent>
    </Card>
  );
}

function CancelCard({ transfer, onDone }: { transfer: Transfer; onDone: () => Promise<void> }) {
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function cancel() {
    setPending(true);
    setError(null);
    try {
      await api(`/api/transfers/${transfer.id}/cancel`, { method: "POST" });
      toast.success("Transfer cancelled and funds released.");
      await onDone();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <Card>
      <CardContent className="flex flex-wrap items-center justify-between gap-4 pt-6">
        <p className="text-muted-foreground text-sm">Changed your mind? Nothing has moved yet.</p>
        <Button variant="outline" onClick={cancel} disabled={pending} aria-busy={pending || undefined}>
          {pending ? "Cancelling…" : "Cancel transfer"}
        </Button>
      </CardContent>
      {error && (
        <CardContent>
          <FormError message={error} />
        </CardContent>
      )}
    </Card>
  );
}
