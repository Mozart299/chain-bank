"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type SubmitEvent } from "react";

import { FormError } from "@/components/form-error";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { formatMoney, parseAmount, type Account, type Transfer } from "@/lib/banking";
import { api, errorMessage } from "@/lib/client";

export default function SendPage() {
  const router = useRouter();
  const [account, setAccount] = useState<Account | null>(null);
  const [payee, setPayee] = useState("");
  const [amountText, setAmountText] = useState("");
  const [reference, setReference] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  // One key per distinct submission: a double-click or network retry of the same form reuses it, so
  // the server returns the original transfer instead of sending twice. Editing the form starts a new key.
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());

  useEffect(() => {
    api<Account[]>("/api/accounts")
      .then((a) => setAccount(a[0] ?? null))
      .catch((e) => setError(errorMessage(e)));
  }, []);

  const amount = parseAmount(amountText);
  const edit = (setter: (v: string) => void) => (v: string) => {
    setter(v);
    setIdempotencyKey(crypto.randomUUID());
  };

  async function onSubmit(e: SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!account || amount === null) {
      setError("Enter a whole-number amount greater than zero.");
      return;
    }
    setPending(true);
    setError(null);
    try {
      const res = await fetchTransfer(account.id);
      router.push(`/account/transfers/${res.id}`);
    } catch (err) {
      setError(errorMessage(err));
      setPending(false);
    }
  }

  async function fetchTransfer(sourceAccountId: string) {
    return api<Transfer>("/api/transfers", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: {
        sourceAccountId,
        destinationAccountNumber: payee.trim(),
        amount,
        currency: account?.currency ?? "xUGX",
        reference: reference.trim() || undefined,
      },
    });
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Send money</CardTitle>
        <CardDescription>
          {account
            ? `From ${account.accountNumber} · ${formatMoney(account.availableBalance, account.currency)} available`
            : "Loading your account…"}
        </CardDescription>
      </CardHeader>
      <form onSubmit={onSubmit} noValidate>
        <CardContent className="grid gap-5">
          <div className="grid gap-2">
            <Label htmlFor="payee">Payee account number</Label>
            <Input
              id="payee"
              value={payee}
              onChange={(e) => edit(setPayee)(e.target.value.toUpperCase())}
              placeholder="e.g. RWZ-10000002"
              autoComplete="off"
              spellCheck={false}
              className="font-mono"
              required
            />
          </div>
          <div className="grid gap-2">
            <Label htmlFor="amount">Amount (xUGX)</Label>
            <Input
              id="amount"
              inputMode="numeric"
              value={amountText}
              onChange={(e) => edit(setAmountText)(e.target.value)}
              aria-describedby="amount-hint"
              aria-invalid={amountText !== "" && amount === null ? true : undefined}
              className="tabular-nums"
              required
            />
            <p id="amount-hint" className="text-muted-foreground text-xs">
              {amount !== null ? formatMoney(amount) : "Whole shillings only."} Over {formatMoney(1_000_000)}, or to a
              new payee, you&apos;ll confirm with your authenticator code.
            </p>
          </div>
          <div className="grid gap-2">
            <Label htmlFor="reference">Reference (optional)</Label>
            <Input
              id="reference"
              value={reference}
              maxLength={140}
              onChange={(e) => edit(setReference)(e.target.value)}
            />
          </div>
          <FormError message={error} />
        </CardContent>
        <CardFooter className="mt-6">
          <Button type="submit" disabled={pending || !account} aria-busy={pending || undefined}>
            {pending ? "Sending…" : amount !== null ? `Send ${formatMoney(amount)}` : "Send"}
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}
