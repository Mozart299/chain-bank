"use client";

import { Landmark, LogOut } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";

import { useSession } from "@/components/session-provider";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export function SiteHeader() {
  const { me, status, signOut } = useSession();
  const pathname = usePathname();

  const navLink = (href: string, label: string) => {
    const active =
      href === "/"
        ? pathname === "/"
        : pathname === href || pathname.startsWith(`${href}/`);
    return (
      <Link
        href={href}
        aria-current={active ? "page" : undefined}
        className={cn(
          buttonVariants({ variant: "ghost", size: "sm" }),
          active && "bg-muted",
        )}
      >
        {label}
      </Link>
    );
  };

  return (
    <header className="bg-background/80 sticky top-0 z-40 border-b backdrop-blur">
      <div className="mx-auto flex h-14 w-full max-w-5xl items-center justify-between gap-4 px-4">
        <div className="flex items-center gap-3">
          <Link
            href="/"
            className="flex items-center gap-2 font-semibold"
            aria-label="ChainBank home"
          >
            <Landmark className="size-5" aria-hidden />
            <span>ChainBank</span>
          </Link>
          <Badge variant="outline" className="hidden sm:inline-flex">
            Simulation
          </Badge>
        </div>

        <nav aria-label="Main" className="flex items-center gap-1">
          {navLink("/", "Home")}
          {status === "authenticated" && me ? (
            <>
              {navLink("/account", "Account")}
              {me.roles.includes("BANK_OPERATOR") &&
                navLink("/operator", "Reviews")}
              <span className="text-muted-foreground hidden px-2 text-sm md:inline">
                {me.displayName}
              </span>
              <Button variant="outline" size="sm" onClick={signOut}>
                <LogOut aria-hidden />
                Sign out
              </Button>
            </>
          ) : status === "anonymous" ? (
            <>
              {navLink("/login", "Sign in")}
              <Link href="/register" className={buttonVariants({ size: "sm" })}>
                Register
              </Link>
            </>
          ) : (
            // Reserve space while the session loads so the header does not jump.
            <span className="w-32" aria-hidden />
          )}
        </nav>
      </div>
    </header>
  );
}

/** Static stand-in rendered during prerendering, before the URL and session are known. */
export function SiteHeaderFallback() {
  return (
    <header className="bg-background/80 sticky top-0 z-40 border-b backdrop-blur">
      <div className="mx-auto flex h-14 w-full max-w-5xl items-center gap-3 px-4">
        <Link
          href="/"
          className="flex items-center gap-2 font-semibold"
          aria-label="ChainBank home"
        >
          <Landmark className="size-5" aria-hidden />
          <span>ChainBank</span>
        </Link>
      </div>
    </header>
  );
}
