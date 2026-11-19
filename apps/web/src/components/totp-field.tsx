"use client";

import { REGEXP_ONLY_DIGITS } from "input-otp";
import { useId } from "react";

import { InputOTP, InputOTPGroup, InputOTPSeparator, InputOTPSlot } from "@/components/ui/input-otp";
import { Label } from "@/components/ui/label";

/** Six-digit authenticator code input. Submits as a normal form field named `name`. */
export function TotpField({
  name = "code",
  label = "Authenticator code",
  description = "6-digit code from your authenticator app",
}: {
  name?: string;
  label?: string;
  description?: string;
}) {
  const id = useId();
  return (
    <div className="grid gap-2">
      <Label htmlFor={id}>{label}</Label>
      <InputOTP
        id={id}
        name={name}
        maxLength={6}
        pattern={REGEXP_ONLY_DIGITS}
        autoComplete="one-time-code"
        aria-describedby={`${id}-hint`}
        required
      >
        <InputOTPGroup>
          <InputOTPSlot index={0} />
          <InputOTPSlot index={1} />
          <InputOTPSlot index={2} />
        </InputOTPGroup>
        <InputOTPSeparator />
        <InputOTPGroup>
          <InputOTPSlot index={3} />
          <InputOTPSlot index={4} />
          <InputOTPSlot index={5} />
        </InputOTPGroup>
      </InputOTP>
      <p id={`${id}-hint`} className="text-muted-foreground text-xs">
        {description}
      </p>
    </div>
  );
}
