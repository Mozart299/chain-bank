"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";

import { useSession } from "@/components/session-provider";
import { cn } from "@/lib/utils";

const TABS = [
  { href: "/account", label: "Overview" },
  { href: "/account/send", label: "Send money" },
  { href: "/account/security", label: "Security" },
];

/** Guards the account area (signed-in users only) and renders its tab navigation. */
export function AccountShell({ children }: { children: ReactNode }) {
  const { me, status } = useSession();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (status === "anonymous") router.replace("/login");
  }, [status, router]);

  if (status !== "authenticated" || !me) {
    return (
      <main className="text-muted-foreground mx-auto w-full max-w-3xl px-4 py-12" aria-live="polite">
        Loading your account…
      </main>
    );
  }

  const tabs = me.roles.includes("CUSTOMER") ? TABS : TABS.filter((t) => t.href === "/account/security");

  return (
    <div className="mx-auto w-full max-w-3xl px-4 py-8">
      <nav aria-label="Account sections" className="mb-8 flex gap-1 overflow-x-auto border-b">
        {tabs.map((t) => {
          const active = t.href === "/account" ? pathname === "/account" : pathname.startsWith(t.href);
          return (
            <Link
              key={t.href}
              href={t.href}
              aria-current={active ? "page" : undefined}
              className={cn(
                "-mb-px border-b-2 px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors",
                active
                  ? "border-foreground text-foreground"
                  : "text-muted-foreground hover:text-foreground border-transparent",
              )}
            >
              {t.label}
            </Link>
          );
        })}
      </nav>
      {children}
    </div>
  );
}
