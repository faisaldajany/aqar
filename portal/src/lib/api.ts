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

export type Listing = {
  id: string;
  title: string;
  district: string;
  propertyType: string;
  purpose: "SALE" | "RENT";
  price: number;
  areaSqm: number | null;
  rooms: number | null;
  adLicense: string;
  licenseExpiry: string;
  vacant: boolean;
  status: "ACTIVE" | "HIDDEN" | "SOLD";
  marketerId: string | null;
  latitude: number | null;
  longitude: number | null;
  videoUrl: string | null;
  photoUrls: string[];
  uploadedPhotos: number;
  description: string | null;
  source: string;
  sourceUrl: string | null;
  selfVisitAccess?: string[];
  accessLockNote?: string | null;
  accessGuard?: string | null;
  accessKeyPoint?: string | null;
};

export type ListingForm = Omit<Listing, "id" | "status" | "uploadedPhotos" | "source"> & { source?: string };

export type ImportField = { field: string; label: string; required: boolean; column: number | null };
export type ImportRow = {
  row: number; title: string | null; district: string | null; purpose: string | null; price: string | null;
  license: string | null; expiry: string | null; status: "READY" | "WARNING" | "INVALID"; errors: string[]; warnings: string[];
};
export type ImportPreview = {
  batchId: string; fileName: string; headers: string[]; fields: ImportField[];
  total: number; ready: number; withWarnings: number; invalid: number; rows: ImportRow[];
};
export type ImportCommitted = { created: number; updated: number; skipped: number; skippedRows: ImportRow[] };
export type AqarDraft = { listing: ListingForm; warnings: string[]; missing: string[] };
export type Alert = { id: string; kind: string; subjectId: string | null; message: string; read: boolean; createdAt: string };
export type Usage = { plan: string; planName: string; conversations: number; included: number; percent: number; overage: number; overageSar: number; marketersLimit: number };
export type Marketer = { id: string; name: string; waPhone: string; districts: string[]; active: boolean };

export function today(): string {
  return new Date().toISOString().slice(0, 10);
}

export function daysUntil(date: string): number {
  return Math.round((new Date(date + "T00:00:00").getTime() - new Date(today() + "T00:00:00").getTime()) / 86400000);
}

export const fmt = (n: number | null | undefined) => (n == null ? "" : Number(n).toLocaleString("en-US"));

/** Google Maps link or "lat, lng" → coordinates. */
export function parseLocation(s: string): { lat: number; lng: number } | null {
  const t = s.trim();
  const m = t.match(/@(-?\d+\.\d+),(-?\d+\.\d+)/) || t.match(/[?&](?:q|query|ll)=(-?\d+\.\d+)(?:,|%2C)(-?\d+\.\d+)/) || t.match(/^(-?\d{1,2}\.\d+)\s*[,،]\s*(-?\d{1,3}\.\d+)$/);
  return m ? { lat: Number(m[1]), lng: Number(m[2]) } : null;
}

export type Booking = {
  id: string; conversationId: string; listingId: string; marketerId: string | null;
  mode: "WITH_MARKETER" | "SELF_VISIT"; startsAt: string; endsAt: string;
  status: "PENDING_APPROVAL" | "CONFIRMED" | "REJECTED" | "CANCELLED" | "DONE" | "NO_SHOW";
  accessMethod: string | null; accessCode: string | null; customerName: string | null; note: string | null;
  confirmation: string | null; listingTitle: string; listingDistrict: string; customerWaId: string; marketerName: string | null;
};

export type BookingSettings = {
  days: number[]; startTime: string; endTime: string; slotMinutes: number; bufferMinutes: number; minLeadMinutes: number;
  reminderMinutes: number; selfVisitEnabled: boolean; selfVisitRequiresApproval: boolean; selfStartTime: string;
  selfEndTime: string; selfVacantOnly: boolean; selfQualifiedOnly: boolean; codeWindowMinutes: number;
  followUpEnabled: boolean; templateRep: string; templateSelf: string;
};

export const DAY_NAMES = ["الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"];
export const ACCESS_LABELS: Record<string, string> = { LOCK: "رمز قفل ذكي", GUARD: "الحارس أو المالك في الموقع", KEY: "استلام المفتاح" };

export function riyadhDay(iso: string): string {
  return new Date(iso).toLocaleDateString("ar-SA-u-ca-gregory", { weekday: "long", day: "numeric", month: "numeric", timeZone: "Asia/Riyadh" });
}
export function riyadhTime(iso: string): string {
  return new Date(iso).toLocaleTimeString("ar-SA", { hour: "numeric", minute: "2-digit", timeZone: "Asia/Riyadh" });
}

export type Lead = {
  customerName?: string | null; purpose?: string | null; propertyType?: string | null; districts?: string | null;
  budgetMin?: number | null; budgetMax?: number | null; payment?: string | null; timeline?: string | null; notes?: string | null;
  temperature?: "HOT" | "WARM" | "COLD";
};
export type Handoff = {
  id: string; conversationId: string; reason: string; summary: string; status: "OPEN" | "CLAIMED" | "CLOSED";
  createdAt: string; marketer: string | null; customer: string;
};
export type ConversationDetails = {
  waId: string; agentActive: boolean; lead: Lead;
  bookings: { id: string; mode: string; startsAt: string; status: string; listing: string }[];
  handoffs: Handoff[];
};
export type Stats = {
  inquiriesToday: number; inquiriesWeek: number; qualifiedWeek: number; bookingsToday: number; bookedWeek: number;
  openHandoffs: number; afterHoursWeek: number; medianReplyMs: number | null;
};
export const TEMP_LABELS: Record<string, [string, string]> = { HOT: ["جاد", "p-bad"], WARM: ["مهتم", "p-warn"], COLD: ["استكشاف", "p-idle"] };
export const PURPOSE_LABELS: Record<string, string> = { SALE: "شراء", BUY: "شراء", RENT: "إيجار" };

export type Plan = { id: "STARTER" | "BASIC" | "GROWTH"; name: string; monthlySar: number; annualSar: number; includedConversations: number; marketers: number; whatsappNumbers: number; overageSar: number | null };
export type Billing = {
  status: "TRIAL" | "ACTIVE" | "PAST_DUE" | "EXPIRED" | "CANCELLED"; plan: Plan["id"]; cycle: "MONTHLY" | "ANNUAL";
  trialEndsAt: string; trialDaysLeft: number; paidUntil: string | null; serving: boolean; vatPercent: number;
  provider: string; mockPayments: boolean; plans: Plan[];
};
export type Invoice = {
  id: string; number: string; kind: "SUBSCRIPTION" | "OVERAGE" | "SETUP"; plan: string | null; cycle: string | null;
  description: string; subtotalHalalas: number; vatHalalas: number; totalHalalas: number; status: "DUE" | "PAID" | "VOID";
  payUrl: string | null; createdAt: string; paidAt: string | null;
};
export type OfficeSettings = {
  name: string; falLicense: string; falStatus: "PENDING_REVIEW" | "VERIFIED" | "REJECTED"; falNote: string | null;
  ownerPhone: string | null; reportEnabled: boolean; reportHour: number;
};
export type Onboarding = {
  steps: { key: string; label: string; href: string; done: boolean }[]; done: number; total: number;
  subscription: Billing["status"]; trialDaysLeft: number; serving: boolean; falStatus: OfficeSettings["falStatus"];
};
export type DailyReportRow = { date: string; body: string; sentTo: string | null; sentAt: string | null; error: string | null };
export const sar = (halalas: number) => (halalas / 100).toLocaleString("en-US", { minimumFractionDigits: halalas % 100 ? 2 : 0, maximumFractionDigits: 2 });
export const SUB_LABELS: Record<Billing["status"], [string, string]> = {
  TRIAL: ["تجربة مجانية", "p-acc"], ACTIVE: ["فعّال", "p-ok"], PAST_DUE: ["بانتظار التجديد", "p-warn"],
  EXPIRED: ["متوقف", "p-bad"], CANCELLED: ["ملغى", "p-idle"],
};
