"use client";

/**
 * Browser-side API client. All calls go to same-origin /api/*, which Next.js rewrites to the
 * Quarkus API, so the HttpOnly session cookie is first-party and never readable by JavaScript.
 * Mutating requests carry the CSRF token obtained from /api/auth/me (or the login response).
 */

export type ApiError = { status: number; code: string; message: string; correlationId?: string };

export type Me = {
  userId: string;
  email: string;
  displayName: string;
  institutionId: string | null;
  roles: string[];
  csrfToken: string;
  stepUpValidUntil: string | null;
  sessionExpiresAt: string;
};

let csrfToken: string | null = null;

export function setCsrfToken(token: string | null) {
  csrfToken = token;
}

export class ApiRequestError extends Error {
  constructor(public readonly error: ApiError) {
    super(error.message);
  }
}

export async function api<T = void>(
  path: string,
  init: { method?: "GET" | "POST" | "DELETE"; body?: unknown } = {},
): Promise<T> {
  const method = init.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json" };
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET" && csrfToken) headers["X-CSRF-Token"] = csrfToken;

  const res = await fetch(path, {
    method,
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
    credentials: "same-origin",
  });

  const newCsrf = res.headers.get("X-CSRF-Token");
  if (newCsrf) setCsrfToken(newCsrf);

  if (!res.ok) {
    let error: ApiError = { status: res.status, code: `HTTP_${res.status}`, message: res.statusText };
    try {
      error = (await res.json()) as ApiError;
    } catch {
      // keep the generic error
    }
    throw new ApiRequestError(error);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export async function loadMe(): Promise<Me | null> {
  try {
    const me = await api<Me>("/api/auth/me");
    setCsrfToken(me.csrfToken);
    return me;
  } catch (e) {
    if (e instanceof ApiRequestError && e.error.status === 401) return null;
    throw e;
  }
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiRequestError) return e.error.message;
  if (e instanceof Error) return e.message;
  return "Something went wrong";
}
