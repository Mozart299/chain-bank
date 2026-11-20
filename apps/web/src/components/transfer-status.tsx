import { Badge } from "@/components/ui/badge";
import { STATUS, type TransferState } from "@/lib/banking";
import { cn } from "@/lib/utils";

const TONES = {
  ok: "border-emerald-600/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  wait: "border-amber-600/40 bg-amber-500/10 text-amber-700 dark:text-amber-400",
  bad: "border-red-600/40 bg-red-500/10 text-red-700 dark:text-red-400",
  info: "border-sky-600/40 bg-sky-500/10 text-sky-700 dark:text-sky-400",
};

export function TransferStatus({ state, className }: { state: TransferState; className?: string }) {
  const s = STATUS[state] ?? { label: state, tone: "info" as const };
  return (
    <Badge variant="outline" className={cn(TONES[s.tone], className)}>
      {s.label}
    </Badge>
  );
}
