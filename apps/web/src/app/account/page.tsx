"use client";

import { ArrowDownLeft, ArrowUpRight, Copy, Send } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { FormError } from "@/components/form-error";
import { useSession } from "@/components/session-provider";
import { TransferStatus } from "@/components/transfer-status";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { awaitingCustomer, formatMoney, signedMoney, type Account, type Transfer } from "@/lib/banking";
import { api, errorMessage } from "@/lib/client";

export default function OverviewPage() {
  const { me } = useSession();
  const [account, setAccount] = useState<Account | null>(null);
  const [transfers, setTransfers] = useState<Transfer[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const isCustomer = me?.roles.includes("CUSTOMER") ?? false;

  useEffect(() => {
    if (!isCustomer) return;
    Promise.all([api<Account[]>("/api/accounts"), api<Transfer[]>("/api/transfers?limit=20")])
      .then(([accounts, list]) => {
        setAccount(accounts[0] ?? null);
        setTransfers(list);
      })
      .catch((e) => setError(errorMessage(e)));
  }, [isCustomer]);

  if (!isCustomer) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Staff account</CardTitle>
          <CardDescription>
            Your role has no customer accounts.{" "}
            {me?.roles.includes("BANK_OPERATOR") && (
              <Link href="/operator" className="text-foreground underline underline-offset-4">
                Open the review queue
              </Link>
            )}
          </CardDescription>
        </CardHeader>
      </Card>
    );
  }

  if (error) return <FormError message={error} />;
  if (!account || !transfers) {
    return (
      <p className="text-muted-foreground" aria-live="polite">
        Loading balances…
      </p>
    );
  }

  const needsAction = transfers.filter(awaitingCustomer);

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardDescription>{account.institution.name} · Current account</CardDescription>
          <CardTitle className="text-3xl tabular-nums">
            {formatMoney(account.availableBalance, account.currency)}
          </CardTitle>
          <p className="text-muted-foreground text-sm">Available balance</p>
        </CardHeader>
        <CardContent className="flex flex-wrap items-center justify-between gap-4">
          <div className="text-muted-foreground space-y-1 text-sm">
            <p className="flex items-center gap-2">
              Account number <span className="text-foreground font-mono">{account.accountNumber}</span>
              <Button
                variant="ghost"
                size="icon-xs"
                aria-label="Copy account number"
                onClick={() => {
                  void navigator.clipboard?.writeText(account.accountNumber);
                  toast.success("Account number copied");
                }}
              >
                <Copy aria-hidden />
              </Button>
            </p>
            {account.reservedBalance > 0 && (
              <p>
                {formatMoney(account.reservedBalance)} held for pending transfers · ledger{" "}
                {formatMoney(account.ledgerBalance)}
              </p>
            )}
          </div>
          <Link href="/account/send" className={buttonVariants()}>
            <Send aria-hidden />
            Send money
          </Link>
        </CardContent>
      </Card>

      {needsAction.length > 0 && (
        <Card className="border-amber-500/50">
          <CardHeader>
            <CardTitle>Waiting for your approval</CardTitle>
            <CardDescription>
              Approve with your authenticator code within 15 minutes, or the funds are released.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <TransferList transfers={needsAction} />
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Recent activity</CardTitle>
        </CardHeader>
        <CardContent>
          {transfers.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              No transfers yet. Your account was opened with {formatMoney(10_000_000)} of demo funds.
            </p>
          ) : (
            <TransferList transfers={transfers} />
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function TransferList({ transfers }: { transfers: Transfer[] }) {
  return (
    <ul className="divide-y">
      {transfers.map((t) => {
        const outgoing = t.direction === "OUTGOING";
        const counterparty = outgoing ? t.destination : t.source;
        return (
          <li key={t.id}>
            <Link
              href={`/account/transfers/${t.id}`}
              className="hover:bg-muted/50 -mx-2 flex items-center gap-3 rounded-md px-2 py-3"
            >
              <span className="bg-muted flex size-8 shrink-0 items-center justify-center rounded-full" aria-hidden>
                {outgoing ? <ArrowUpRight className="size-4" /> : <ArrowDownLeft className="size-4" />}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">
                  {outgoing ? "To" : "From"} {counterparty.holder}
                </span>
                <span className="text-muted-foreground block truncate text-xs">
                  {counterparty.bank} · {new Date(t.createdAt).toLocaleString()}
                  {t.reference ? ` · ${t.reference}` : ""}
                </span>
                <TransferStatus state={t.state} className="mt-1 sm:hidden" />
              </span>
              <span className="flex flex-col items-end gap-1">
                <span className="text-sm font-medium whitespace-nowrap tabular-nums">
                  {signedMoney(outgoing ? -t.amount : t.amount, t.currency)}
                </span>
                <TransferStatus state={t.state} className="hidden sm:inline-flex" />
              </span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
