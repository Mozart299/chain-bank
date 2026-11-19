import { TriangleAlert } from "lucide-react";
import Link from "next/link";
import { connection } from "next/server";
import { Suspense } from "react";

import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { getInstitutions, getReadiness } from "@/lib/api";

export default function Home() {
  return (
    <main className="mx-auto w-full max-w-3xl space-y-8 px-4 py-10">
      <Alert>
        <TriangleAlert aria-hidden />
        <AlertDescription>Simulation only. No real money, customer data or bank deposits are involved.</AlertDescription>
      </Alert>
      <div className="space-y-2">
        <h1 className="text-3xl font-semibold tracking-tight">ChainBank</h1>
        <p className="text-muted-foreground">
          Two-bank sandbox: double-entry ledger off-chain, settlement tokens on a local EVM chain.
        </p>
      </div>
      <nav aria-label="Account" className="flex flex-wrap gap-3">
        <Link href="/login" className={buttonVariants()}>
          Sign in
        </Link>
        <Link href="/register" className={buttonVariants({ variant: "outline" })}>
          Register
        </Link>
        <Link href="/account" className={buttonVariants({ variant: "link" })}>
          My account
        </Link>
      </nav>
      <Suspense fallback={<p className="text-muted-foreground" aria-live="polite">Checking backend…</p>}>
        <SystemStatus />
      </Suspense>
    </main>
  );
}

async function SystemStatus() {
  await connection();
  const result = await Promise.all([getReadiness(), getInstitutions()]).catch(() => null);
  if (!result) {
    return (
      <Alert variant="destructive">
        <TriangleAlert aria-hidden />
        <AlertDescription>
          Backend unreachable. Start it with <code>make up</code> or <code>make api-dev</code>.
        </AlertDescription>
      </Alert>
    );
  }
  const [readiness, institutions] = result;
  const ledger = readiness.checks.find((c) => c.name === "ledger-invariants");
  const up = readiness.status === "UP";
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-3">
          System status
          <Badge variant={up ? "secondary" : "destructive"}>{readiness.status}</Badge>
        </CardTitle>
        {ledger?.data && (
          <CardDescription>
            Unbalanced journals: {String(ledger.data.unbalancedJournals)} · Drifted balances:{" "}
            {String(ledger.data.driftedBalances)}
          </CardDescription>
        )}
      </CardHeader>
      <CardContent>
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Sandbox institutions</caption>
          <thead className="text-muted-foreground">
            <tr className="border-b">
              <th scope="col" className="py-2 font-medium">Code</th>
              <th scope="col" className="font-medium">Name</th>
              <th scope="col" className="font-medium">On-chain pseudonym</th>
              <th scope="col" className="font-medium">Status</th>
            </tr>
          </thead>
          <tbody>
            {institutions.map((i) => (
              <tr key={i.id} className="border-b last:border-0">
                <td className="py-2 font-mono">{i.code}</td>
                <td>{i.name}</td>
                <td className="font-mono">{i.pseudonym}</td>
                <td>{i.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </CardContent>
    </Card>
  );
}
