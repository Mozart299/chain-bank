import { connection } from "next/server";
import { Suspense } from "react";

import { getInstitutions, getReadiness } from "@/lib/api";

export default function Home() {
  return (
    <main className="mx-auto w-full max-w-3xl px-4 py-10">
      <p
        role="note"
        className="mb-6 rounded border border-amber-400 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:bg-amber-950 dark:text-amber-100"
      >
        Simulation only. No real money, customer data or bank deposits are involved.
      </p>
      <h1 className="text-3xl font-semibold">ChainBank</h1>
      <p className="mt-2 text-zinc-600 dark:text-zinc-400">
        Two-bank sandbox: double-entry ledger off-chain, settlement tokens on a local EVM chain.
      </p>
      <Suspense fallback={<p className="mt-8" aria-live="polite">Checking backend…</p>}>
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
      <p role="alert" className="mt-8 text-red-600">
        Backend unreachable. Start it with <code>make up</code> or <code>make api-dev</code>.
      </p>
    );
  }
  const [readiness, institutions] = result;
  const ledger = readiness.checks.find((c) => c.name === "ledger-invariants");
  return (
    <section aria-labelledby="status-heading" className="mt-8 space-y-6">
      <h2 id="status-heading" className="text-xl font-medium">
        System status:{" "}
        <span className={readiness.status === "UP" ? "text-emerald-600" : "text-red-600"}>
          {readiness.status}
        </span>
      </h2>
      {ledger?.data && (
        <p className="text-sm">
          Unbalanced journals: {String(ledger.data.unbalancedJournals)} · Drifted balances:{" "}
          {String(ledger.data.driftedBalances)}
        </p>
      )}
      <table className="w-full text-left text-sm">
        <caption className="sr-only">Sandbox institutions</caption>
        <thead>
          <tr className="border-b border-zinc-300 dark:border-zinc-700">
            <th scope="col" className="py-2">Code</th>
            <th scope="col">Name</th>
            <th scope="col">On-chain pseudonym</th>
            <th scope="col">Status</th>
          </tr>
        </thead>
        <tbody>
          {institutions.map((i) => (
            <tr key={i.id} className="border-b border-zinc-200 dark:border-zinc-800">
              <td className="py-2 font-mono">{i.code}</td>
              <td>{i.name}</td>
              <td className="font-mono">{i.pseudonym}</td>
              <td>{i.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
