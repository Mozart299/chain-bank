export type Account = {
  id: string;
  accountNumber: string;
  institution: { code: string; name: string };
  currency: string;
  ledgerBalance: number;
  reservedBalance: number;
  availableBalance: number;
  status: string;
};

export type Entry = {
  lineId: number;
  postedAt: string;
  journalReference: string;
  description: string;
  transferId: string | null;
  amount: number;
  direction: "IN" | "OUT";
};

export type Party = { accountNumber: string; bank: string; holder: string };

export type TransferState =
  | "REQUESTED"
  | "VALIDATED"
  | "APPROVED"
  | "SUBMITTED"
  | "CONFIRMED"
  | "RECONCILED"
  | "REJECTED"
  | "FAILED"
  | "CANCELLED"
  | "PENDING_INVESTIGATION";

export type Transfer = {
  id: string;
  state: TransferState;
  direction: "OUTGOING" | "INCOMING";
  amount: number;
  currency: string;
  reference: string | null;
  source: Party;
  destination: Party;
  crossBank: boolean;
  requiresStepUp: boolean;
  stepUpApproved: boolean;
  requiresReview: boolean;
  operatorApproved: boolean;
  failureReason: string | null;
  createdAt: string;
  expiresAt: string;
  approvedAt: string | null;
  events: { fromState: string | null; toState: string; reason: string | null; at: string }[];
};

/** Amounts are integer minor units end to end; xUGX has no decimals. */
export function formatMoney(amount: number, currency = "xUGX"): string {
  return `${currency} ${Math.abs(amount).toLocaleString("en-US", { maximumFractionDigits: 0 })}`;
}

export function signedMoney(amount: number, currency = "xUGX"): string {
  return `${amount < 0 ? "−" : "+"}${formatMoney(amount, currency)}`;
}

/** Parses user input like "1,500,000" into a safe positive integer, or null. */
export function parseAmount(input: string): number | null {
  const cleaned = input.replace(/[,\s_]/g, "");
  if (!/^\d{1,13}$/.test(cleaned)) return null;
  const n = Number(cleaned);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
}

export function isPending(t: Transfer): boolean {
  return t.state === "VALIDATED" || t.state === "REQUESTED";
}

export function awaitingCustomer(t: Transfer): boolean {
  return t.direction === "OUTGOING" && t.state === "VALIDATED" && t.requiresStepUp && !t.stepUpApproved;
}

export function awaitingReview(t: Transfer): boolean {
  return t.state === "VALIDATED" && t.requiresReview && !t.operatorApproved;
}

export const STATUS: Record<TransferState, { label: string; tone: "ok" | "wait" | "bad" | "info" }> = {
  REQUESTED: { label: "Requested", tone: "wait" },
  VALIDATED: { label: "Awaiting approval", tone: "wait" },
  APPROVED: { label: "Posted · awaiting settlement", tone: "info" },
  SUBMITTED: { label: "Settling on-chain", tone: "info" },
  CONFIRMED: { label: "Completed", tone: "ok" },
  RECONCILED: { label: "Completed · reconciled", tone: "ok" },
  REJECTED: { label: "Rejected", tone: "bad" },
  FAILED: { label: "Failed", tone: "bad" },
  CANCELLED: { label: "Cancelled", tone: "bad" },
  PENDING_INVESTIGATION: { label: "Under investigation", tone: "wait" },
};

export const REASONS: Record<string, string> = {
  INSUFFICIENT_FUNDS: "Not enough available funds.",
  DAILY_LIMIT: "This would exceed your daily limit of xUGX 5,000,000.",
  OPERATOR_REJECTED: "Your bank declined this transfer after review.",
  CANCELLED_BY_CUSTOMER: "You cancelled this transfer.",
  EXPIRED: "Not approved within 15 minutes, so it was cancelled and the funds released.",
  BOOK_TRANSFER: "Both accounts are at the same bank, so it completed instantly.",
};
