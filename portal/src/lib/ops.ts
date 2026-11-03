import type { Billing, Invoice } from "./api";
import type { TemplateRow } from "@/components/TemplateStatus";

/** Operator console (/ops) data, from /api/ops/*. */

export type OpsOffice = {
  id: string; name: string; falLicense: string; falStatus: "PENDING_REVIEW" | "VERIFIED" | "REJECTED"; falNote: string | null;
  subscription: Billing["status"]; plan: "STARTER" | "BASIC" | "GROWTH"; cycle: "MONTHLY" | "ANNUAL";
  trialEndsAt: string; paidUntil: string | null; createdAt: string; suspended: boolean; suspendedReason: string | null;
  ownerPhone: string | null; whatsappConnected: boolean; listings: number; promoCode: string | null;
  users: number; lastLoginAt: string | null; ownerEmail: string | null; conversationsThisMonth: number;
  /** TEST = internal Hidaa team office (confirmed company email): pays with Moyasar test cards, not counted as revenue. */
  paymentMode?: "LIVE" | "TEST" | "INTERNAL_UNCONFIRMED";
};

export type OpsUser = {
  id: string; fullName: string; email: string; role: string; createdAt: string; lastLoginAt: string | null; disabled: boolean;
};

export type OpsAudit = { at: string; action: string; officeId: string | null; target: string | null; detail: string | null };

export type OpsDetail = Omit<OpsOffice, "users"> & {
  users: OpsUser[]; invoices: Invoice[];
  promo: { code: string; summary: string; periodsUsed: number; usedUp: boolean } | null;
  usage: { conversations: number; included: number; overage: number };
  audit: OpsAudit[];
  templates?: TemplateRow[];
  /** Where the office is in the WhatsApp setup guide. */
  waSetup?: { done: number; total: number; current: string | null; phone: string | null; readyOn: string | null; hasBusinessApp: boolean | null;
    steps: { key: string; label: string; done: boolean }[] };
};

export type OpsOverview = {
  offices: number; byStatus: Record<Billing["status"], number>; suspended: number; falPending: number;
  whatsappConnected: number; conversationsThisMonth: number; mrrSar: number; supportUnforwarded: number; mailConfigured: boolean;
};

export type OpsPromo = {
  id: string; code: string; kind: "PERCENT" | "FIXED" | "TRIAL_DAYS"; summary: string; redemptions: number;
  maxRedemptions: number | null; newCustomersOnly: boolean; startsAt: string | null; expiresAt: string | null;
  active: boolean; note: string | null; createdAt: string;
};

export type OpsSupport = {
  id: string; createdAt: string; name: string; email: string | null; phone: string | null; topic: string; message: string;
  source: string; forwarded: boolean; forwardError: string | null;
};

export type OpsInvoice = {
  id: string; officeId: string; office: string; number: string; kind: "SUBSCRIPTION" | "OVERAGE" | "SETUP" | "COMP";
  plan: string | null; cycle: string | null; description: string; subtotal: number; vat: number; total: number;
  status: "DUE" | "PAID" | "VOID" | "REFUNDED"; provider: string | null; createdAt: string; paidAt: string | null; discount: number; promoCode: string | null;
  refunded: number; refundedAt: string | null; lastFailureReason: string | null; lastFailureAt: string | null;
  /** Paid with Moyasar test cards (internal office): shown, not counted. */
  test: boolean;
};

export type OpsRevenue = {
  mrr: number; arr: number; payingOffices: number; unpaidTotal: number; unpaidCount: number;
  /** total = collected minus refunds made that month (before VAT). */
  months: { month: string; subscriptions: number; overage: number; setup: number; refunds: number; total: number; vat: number }[];
  invoices: OpsInvoice[];
};

export type OpsUsageRow = {
  officeId: string; office: string; plan: OpsOffice["plan"]; status: string; conversations: number; replies: number; errors: number;
  inputTokens: number; outputTokens: number; aiCost: number; revenue: number; margin: number;
};

export type OpsUsage = {
  month: string; prices: { inputUsdPerMillion: number; outputUsdPerMillion: number; usdToSar: number };
  totals: Omit<OpsUsageRow, "officeId" | "office" | "plan" | "status"> & { costPerReply: number | null };
  offices: OpsUsageRow[];
};

export type FunnelStep = "SIGNED_UP" | "WHATSAPP" | "LISTINGS" | "CONVERSATION" | "PAID";
export type OpsFunnel = {
  days: number; steps: { key: FunnelStep; count: number }[];
  stuck: { officeId: string; office: string; createdAt: string; status: string; next: FunnelStep }[];
};

export const FUNNEL_LABELS: Record<FunnelStep, string> = {
  SIGNED_UP: "سجّل", WHATSAPP: "ربط واتساب حقيقي", LISTINGS: "أضاف عقارات", CONVERSATION: "وصله أول عميل", PAID: "اشترك ودفع",
};

/** 1234.5 → "1,234.50" */
export function sar(n: number | null | undefined): string {
  if (n == null) return "—";
  return Number(n).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export type ReviewIssue = { turn: number; type: string; detail: string };
export type AgentReview = { score: number; verdict: "OK" | "ISSUE"; summary: string; issues: ReviewIssue[]; model: string; createdAt: string; stale: boolean };
export type ReviewRow = {
  officeId: string; office: string; conversationId: string; customer: string; createdAt: string; lastMessageAt: string | null;
  messages: number; agentReplies: number; handedOver: boolean; flags: string[]; review: AgentReview | null;
};
export type OpsReviews = {
  from: string; to: string; conversations: number; withAgentReplies: number; flagged: number; reviewed: number; issues: number;
  avgScore: number | null; toReview: number; byFlag: Record<string, number>; byIssue: Record<string, number>; rows: ReviewRow[];
  offices: { id: string; name: string }[];
};
export type ReviewRun = { reviewed: { conversationId: string; score: number; verdict: string }[]; failed: number; error: string | null; remaining: number };
export type TranscriptMessage = {
  n: number; at: string; sender: "CUSTOMER" | "AGENT" | "HUMAN" | "SYSTEM"; direction: "IN" | "OUT"; type: string; text: string | null;
  run: { outcome: string; latencyMs: number; model: string; inputTokens: number; outputTokens: number; error: string | null;
    tools: { name: string; args: unknown; result: unknown }[] } | null;
};
export type OpsConversation = ReviewRow & {
  lead: Record<string, string | number | null> | null; handoffs: { at: string; reason: string; summary: string }[]; bookings: number;
  withMarketer: boolean; flagList: { code: string; n: number | null; detail: string | null }[]; messages: TranscriptMessage[];
};

export const FLAG_LABELS: Record<string, string> = {
  ERROR: "خطأ في الوكيل", FALLBACK: "رد احتياطي (تعذّر النموذج)", SLOW: "رد بطيء", UNANSWERED: "رسالة بلا رد", REPEAT: "رد مكرر",
  LONG: "رد طويل", ASKED_HUMAN: "طلب موظفاً ولم يُحوَّل", COMPLAINT: "العميل اشتكى",
};

export const ISSUE_LABELS: Record<string, string> = {
  INACCURATE: "معلومة غير دقيقة", MADE_UP: "اختلق معلومة", MISSED_QUESTION: "تجاهل سؤالاً", NO_HANDOFF: "لم يحوّل لموظف",
  BAD_HANDOFF: "حوّل بلا داعٍ", RULE_BROKEN: "خالف تعليماته", TONE: "الأسلوب", REPETITIVE: "تكرار", OTHER: "أخرى",
};

export function scorePill(score: number) {
  return score >= 4 ? "p-ok" : score === 3 ? "p-warn" : "p-bad";
}

export const PLAN_NAMES: Record<OpsOffice["plan"], string> = { STARTER: "البداية", BASIC: "الأساسي", GROWTH: "النمو" };

export const FAL_LABELS: Record<OpsOffice["falStatus"], [string, string]> = {
  PENDING_REVIEW: ["فال قيد التحقق", "p-warn"], VERIFIED: ["فال موثّقة", "p-ok"], REJECTED: ["فال مرفوضة", "p-bad"],
};

export const ACTION_LABELS: Record<string, string> = {
  LOGIN: "دخول للإدارة", FAL_VERIFIED: "توثيق فال", FAL_REJECTED: "رفض فال", TRIAL_EXTENDED: "تمديد التجربة",
  COMP_MONTHS: "أشهر مجانية", PLAN_CHANGED: "تغيير الباقة", SUSPENDED: "إيقاف المكتب", REACTIVATED: "إعادة تفعيل المكتب",
  USER_DISABLED: "إيقاف مستخدم", USER_ENABLED: "تفعيل مستخدم", RESET_LINK: "رابط كلمة مرور", PROMO_CREATED: "كود خصم جديد",
  PROMO_ON: "تفعيل كود", PROMO_OFF: "إيقاف كود", TEMPLATES_SYNC: "تحديث قوالب واتساب", INVOICE_PAID: "تسجيل دفعة يدوية",
  AI_REVIEW: "مراجعة ردود بالذكاء الاصطناعي", CONVERSATION_VIEWED: "فتح محادثة", EXPORT: "تصدير محادثات",
};

/** "2 نوفمبر 2026، 3:15 م" in Riyadh time, or "—". */
export function when(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("ar-SA-u-ca-gregory-nu-latn", {
    day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit", timeZone: "Asia/Riyadh",
  });
}

export function day(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("ar-SA-u-ca-gregory-nu-latn", { day: "numeric", month: "short", year: "numeric", timeZone: "Asia/Riyadh" });
}
