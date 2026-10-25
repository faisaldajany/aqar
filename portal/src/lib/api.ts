export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

/** Calls the Spring API through the same-origin /api proxy; the session cookie rides along. */
export async function api<T>(path: string, init?: RequestInit & { json?: unknown }): Promise<T> {
  const { json, ...rest } = init ?? {};
  const res = await fetch(`/api${path}`, {
    ...rest,
    headers: { ...(json !== undefined ? { "Content-Type": "application/json" } : {}), ...rest.headers },
    body: json !== undefined ? JSON.stringify(json) : rest.body,
    credentials: "same-origin",
    cache: "no-store",
  });
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) {
    throw new ApiError(res.status, data?.error ?? "error", data?.message ?? "حدث خطأ غير متوقع. حاول مرة أخرى.");
  }
  return data as T;
}

export type Me = {
  user: { id: string; email: string; fullName: string; role: string };
  office: { id: string; name: string; falLicense: string; status: string };
  whatsapp: { connected: boolean; displayPhone?: string; verifiedName?: string; coexistence?: boolean; status?: string };
};

export type Conversation = {
  id: string;
  waId: string;
  name: string | null;
  lastMessageAt: string | null;
  lastInboundAt: string | null;
  agentActive: boolean;
  takenOver: boolean;
  lastBody: string | null;
  lastSender: string | null;
};

export type Message = {
  id: string;
  direction: "IN" | "OUT";
  sender: "CUSTOMER" | "AGENT" | "HUMAN" | "SYSTEM";
  type: string;
  body: string | null;
  mediaId: string | null;
  status: string | null;
  createdAt: string;
};

export function timeAr(iso: string | null): string {
  if (!iso) return "";
  return new Date(iso).toLocaleTimeString("ar-SA", { hour: "numeric", minute: "2-digit", timeZone: "Asia/Riyadh" });
}

export function windowOpen(lastInboundAt: string | null): boolean {
  if (!lastInboundAt) return false;
  return Date.now() < new Date(lastInboundAt).getTime() + (24 * 60 - 5) * 60 * 1000;
}
