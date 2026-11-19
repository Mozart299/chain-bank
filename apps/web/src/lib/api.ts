import "server-only";

const API_URL = process.env.CHAINBANK_API_URL ?? "http://localhost:8080";

export type Institution = {
  id: string;
  code: string;
  name: string;
  pseudonym: string;
  status: string;
};

export type Readiness = {
  status: "UP" | "DOWN";
  checks: { name: string; status: "UP" | "DOWN"; data?: Record<string, unknown> }[];
};

async function getJson<T>(path: string): Promise<T> {
  // Health returns 503 with a JSON body when DOWN, so read the body regardless of status.
  const res = await fetch(`${API_URL}${path}`, { cache: "no-store", signal: AbortSignal.timeout(3000) });
  if (!res.ok && res.status !== 503) {
    throw new Error(`API ${path} responded ${res.status}`);
  }
  return (await res.json()) as T;
}

export const getInstitutions = () => getJson<Institution[]>("/api/institutions");
export const getReadiness = () => getJson<Readiness>("/q/health/ready");
