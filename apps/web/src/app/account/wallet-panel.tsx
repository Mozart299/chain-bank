"use client";

import { Info, Wallet } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { useConnect, useConnection, useConnectors, useDisconnect, useSignMessage } from "wagmi";
import { anvil } from "wagmi/chains";

import { FormError } from "@/components/form-error";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { api, errorMessage } from "@/lib/client";

type Link = { id: string; address: string; chainId: number; verifiedAt: string };
type Challenge = { challengeId: string; message: string; expiresAt: string };

/**
 * Wallet linking, spec section 7.2:
 *   1. ask the API for a one-time challenge (an EIP-4361 message bound to domain, address, chain, nonce, expiry)
 *   2. the wallet signs that exact text (personal_sign); the private key never leaves the wallet
 *   3. the API recovers the signer from the signature and links the address if it matches
 */
export function WalletPanel({ stepUpActive }: { stepUpActive: boolean }) {
  const connection = useConnection();
  const connectors = useConnectors();
  const connect = useConnect();
  const disconnect = useDisconnect();
  const signMessage = useSignMessage();
  const [links, setLinks] = useState<Link[]>([]);
  const [signedMessage, setSignedMessage] = useState<string | null>(null);
  const [progress, setProgress] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLinks(await api<Link[]>("/api/wallet"));
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- initial fetch from the API
    reload().catch((e) => setError(errorMessage(e)));
  }, [reload]);

  const address = connection.address?.toLowerCase();
  const alreadyLinked = address !== undefined && links.some((l) => l.address === address);
  const needsStepUp = links.length > 0 && !stepUpActive;
  const injected = connectors[0];

  async function link() {
    if (!address) return;
    setError(null);
    try {
      setProgress("Requesting a one-time challenge…");
      const challenge = await api<Challenge>("/api/wallet/challenge", {
        method: "POST",
        body: { address, chainId: anvil.id },
      });
      setSignedMessage(challenge.message);
      setProgress("Approve the signature request in your wallet…");
      const signature = await signMessage.mutateAsync({ message: challenge.message });
      setProgress("Verifying signature…");
      await api("/api/wallet/verify", { method: "POST", body: { challengeId: challenge.challengeId, signature } });
      await reload();
      toast.success("Wallet linked.");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setProgress(null);
    }
  }

  async function unlink(id: string) {
    setError(null);
    try {
      await api(`/api/wallet/${id}`, { method: "DELETE" });
      await reload();
      toast.success("Wallet unlinked.");
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Linked wallets</CardTitle>
        <CardDescription>
          Linking proves you control an Ethereum address on the local chain (Anvil, chain id {anvil.id}). It does not
          sign you in and moves no funds.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        {links.length === 0 ? (
          <p className="text-muted-foreground text-sm">No wallets linked yet.</p>
        ) : (
          <ul className="grid gap-2">
            {links.map((l) => (
              <li key={l.id} className="flex items-center justify-between gap-3 rounded-md border px-3 py-2">
                <span className="font-mono text-sm break-all">{l.address}</span>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => unlink(l.id)}
                  disabled={!stepUpActive}
                  aria-label={`Unlink ${l.address}`}
                >
                  Unlink
                </Button>
              </li>
            ))}
          </ul>
        )}
        {links.length > 0 && !stepUpActive && (
          <p className="text-muted-foreground text-xs">Unlinking requires step-up verification.</p>
        )}

        <Separator />

        {!injected ? (
          <Alert>
            <Info aria-hidden />
            <AlertDescription>No browser wallet detected. Install MetaMask (or similar) to link a wallet.</AlertDescription>
          </Alert>
        ) : connection.status !== "connected" ? (
          <div>
            <Button onClick={() => connect.mutate({ connector: injected })} disabled={connect.isPending}>
              <Wallet aria-hidden />
              {connect.isPending ? "Connecting…" : "Connect browser wallet"}
            </Button>
          </div>
        ) : (
          <div className="grid gap-3">
            <p className="text-sm">
              Connected: <span className="font-mono break-all">{address}</span>
            </p>
            {connection.chainId !== anvil.id && (
              <p className="text-sm text-amber-700 dark:text-amber-400">
                Switch your wallet to chain {anvil.id} (Anvil, http://localhost:8545).
              </p>
            )}
            <div className="flex flex-wrap gap-3">
              <Button
                onClick={link}
                disabled={alreadyLinked || needsStepUp || progress !== null}
                aria-busy={progress !== null || undefined}
              >
                {alreadyLinked ? "Already linked" : "Link this wallet"}
              </Button>
              <Button variant="outline" onClick={() => disconnect.mutate()}>
                Disconnect
              </Button>
            </div>
            {needsStepUp && !alreadyLinked && (
              <p className="text-muted-foreground text-xs">Adding another wallet requires step-up verification.</p>
            )}
          </div>
        )}

        {progress && (
          <p role="status" className="text-muted-foreground text-sm">
            {progress}
          </p>
        )}
        <FormError message={error} />

        {signedMessage && (
          <details className="text-xs">
            <summary className="cursor-pointer">Message you signed</summary>
            <pre className="bg-muted mt-2 overflow-x-auto rounded-md p-3 whitespace-pre-wrap">{signedMessage}</pre>
          </details>
        )}
      </CardContent>
    </Card>
  );
}
