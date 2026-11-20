"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import QRCode from "qrcode";
import { useState, type SubmitEvent } from "react";
import { toast } from "sonner";

import { FormError } from "@/components/form-error";
import { useRedirectIfAuthenticated } from "@/components/session-provider";
import { TotpField } from "@/components/totp-field";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { api, errorMessage } from "@/lib/client";

type Enrollment = { userId: string; otpauthUri: string; totpSecret: string };

export default function RegisterPage() {
  const router = useRouter();
  useRedirectIfAuthenticated();
  const [enrollment, setEnrollment] = useState<Enrollment | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [credentials, setCredentials] = useState<{ email: string; password: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function onRegister(e: SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const email = String(form.get("email"));
    const password = String(form.get("password"));
    setPending(true);
    setError(null);
    try {
      const result = await api<Enrollment>("/api/auth/register", {
        method: "POST",
        body: {
          email,
          password,
          displayName: form.get("displayName"),
          institutionCode: form.get("institutionCode"),
        },
      });
      setQr(await QRCode.toDataURL(result.otpauthUri, { margin: 1, width: 200 }));
      setEnrollment(result);
      setCredentials({ email, password });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setPending(false);
    }
  }

  async function onConfirm(e: SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!credentials) return;
    const form = new FormData(e.currentTarget);
    setPending(true);
    setError(null);
    try {
      await api("/api/auth/totp/confirm", { method: "POST", body: { ...credentials, code: form.get("code") } });
      setCredentials(null);
      toast.success("Account activated. Sign in to continue.");
      router.push("/login");
    } catch (err) {
      setError(errorMessage(err));
      setPending(false);
    }
  }

  return (
    <main className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center px-4 py-12">
      <Card>
        {!enrollment ? (
          <>
            <CardHeader>
              <CardTitle className="text-xl">Create a demo account</CardTitle>
              <CardDescription>Simulation only. Use a made-up email; no real identity is needed.</CardDescription>
            </CardHeader>
            <form onSubmit={onRegister} noValidate>
              <CardContent className="grid gap-4">
                <div className="grid gap-2">
                  <Label htmlFor="displayName">Display name</Label>
                  <Input id="displayName" name="displayName" maxLength={80} required />
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="email">Email</Label>
                  <Input id="email" name="email" type="email" autoComplete="username" required />
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="password">Password</Label>
                  <Input
                    id="password"
                    name="password"
                    type="password"
                    autoComplete="new-password"
                    minLength={12}
                    aria-describedby="password-hint"
                    required
                  />
                  <p id="password-hint" className="text-muted-foreground text-xs">
                    At least 12 characters.
                  </p>
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="institutionCode">Bank</Label>
                  <Select name="institutionCode" defaultValue="BANK_A">
                    <SelectTrigger id="institutionCode" className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="BANK_A">Nile Demo Bank</SelectItem>
                      <SelectItem value="BANK_B">Rwenzori Demo Bank</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <FormError message={error} />
              </CardContent>
              <CardFooter className="mt-6 flex-col gap-4">
                <Button type="submit" className="w-full" disabled={pending} aria-busy={pending || undefined}>
                  {pending ? "Creating…" : "Continue"}
                </Button>
                <p className="text-muted-foreground text-sm">
                  Already registered?{" "}
                  <Link href="/login" className="text-foreground underline underline-offset-4">
                    Sign in
                  </Link>
                </p>
              </CardFooter>
            </form>
          </>
        ) : (
          <>
            <CardHeader>
              <CardTitle className="text-xl">Set up two-factor authentication</CardTitle>
              <CardDescription>
                Scan the QR code with an authenticator app (Google Authenticator, 1Password, Authy…), then enter the
                code it shows.
              </CardDescription>
            </CardHeader>
            <form onSubmit={onConfirm} noValidate>
              <CardContent className="grid gap-4">
                {qr && (
                  // eslint-disable-next-line @next/next/no-img-element -- data URL, nothing to optimise
                  <img
                    src={qr}
                    alt="QR code for adding ChainBank to an authenticator app"
                    width={200}
                    height={200}
                    className="mx-auto rounded-md bg-white p-2"
                  />
                )}
                <p className="text-muted-foreground text-xs">
                  Can&apos;t scan? Enter this key manually:{" "}
                  <code className="text-foreground font-mono break-all">{enrollment.totpSecret}</code>
                </p>
                <TotpField />
                <FormError message={error} />
              </CardContent>
              <CardFooter className="mt-6">
                <Button type="submit" className="w-full" disabled={pending} aria-busy={pending || undefined}>
                  {pending ? "Activating…" : "Activate account"}
                </Button>
              </CardFooter>
            </form>
          </>
        )}
      </Card>
    </main>
  );
}
