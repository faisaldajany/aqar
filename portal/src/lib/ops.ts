import type { Billing, Invoice } from "./api";

/** Operator console (/ops) data, from /api/ops/*. */

export type OpsOffice = {
  id: string; name: string; falLicense: string; falStatus: "PENDING_REVIEW" | "VERIFIED" | "REJECTED"; falNote: string | null;
  subscription: Billing["status"]; plan: "STARTER" | "BASIC" | "GROWTH"; cycle: "MONTHLY" | "ANNUAL";
  trialEndsAt: string; paidUntil: string | null; createdAt: string; suspended: boolean; suspendedReason: string | null;
  ownerPhone: string | null; whatsappConnected: boolean; listings: number; promoCode: string | null;
  users: number; lastLoginAt: string | null; ownerEmail: string | null; conversationsThisMonth: number;
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

export const PLAN_NAMES: Record<OpsOffice["plan"], string> = { STARTER: "البداية", BASIC: "الأساسي", GROWTH: "النمو" };

export const FAL_LABELS: Record<OpsOffice["falStatus"], [string, string]> = {
  PENDING_REVIEW: ["فال قيد التحقق", "p-warn"], VERIFIED: ["فال موثّقة", "p-ok"], REJECTED: ["فال مرفوضة", "p-bad"],
};

export const ACTION_LABELS: Record<string, string> = {
  LOGIN: "دخول للإدارة", FAL_VERIFIED: "توثيق فال", FAL_REJECTED: "رفض فال", TRIAL_EXTENDED: "تمديد التجربة",
  COMP_MONTHS: "أشهر مجانية", PLAN_CHANGED: "تغيير الباقة", SUSPENDED: "إيقاف المكتب", REACTIVATED: "إعادة تفعيل المكتب",
  USER_DISABLED: "إيقاف مستخدم", USER_ENABLED: "تفعيل مستخدم", RESET_LINK: "رابط كلمة مرور", PROMO_CREATED: "كود خصم جديد",
  PROMO_ON: "تفعيل كود", PROMO_OFF: "إيقاف كود",
};

/** "2 نوفمبر 2026، 3:15 م" in Riyadh time, or "—". */
export function when(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("ar-SA-u-ca-gregory", {
    day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit", timeZone: "Asia/Riyadh",
  });
}

export function day(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("ar-SA-u-ca-gregory", { day: "numeric", month: "short", year: "numeric", timeZone: "Asia/Riyadh" });
}
