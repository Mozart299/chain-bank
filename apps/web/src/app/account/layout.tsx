import { Suspense, type ReactNode } from "react";

import { AccountShell } from "./account-shell";
import { Providers } from "./providers";

export default function AccountLayout({ children }: { children: ReactNode }) {
  return (
    <Providers>
      <Suspense
        fallback={<main className="text-muted-foreground mx-auto w-full max-w-3xl px-4 py-12">Loading your account…</main>}
      >
        <AccountShell>{children}</AccountShell>
      </Suspense>
    </Providers>
  );
}
