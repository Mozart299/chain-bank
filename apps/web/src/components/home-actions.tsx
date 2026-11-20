"use client";

import Link from "next/link";

import { useSession } from "@/components/session-provider";
import { buttonVariants } from "@/components/ui/button";

export function HomeActions() {
  const { me, status } = useSession();
  if (status === "loading") return <div className="h-9" aria-hidden />;
  if (status === "authenticated" && me) {
    return (
      <div className="flex flex-wrap items-center gap-3">
        <Link href="/account" className={buttonVariants()}>
          Go to your account
        </Link>
        <span className="text-muted-foreground text-sm">Signed in as {me.email}</span>
      </div>
    );
  }
  return (
    <div className="flex flex-wrap gap-3">
      <Link href="/login" className={buttonVariants()}>
        Sign in
      </Link>
      <Link href="/register" className={buttonVariants({ variant: "outline" })}>
        Create a demo account
      </Link>
    </div>
  );
}
