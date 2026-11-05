import type { Billing } from "./api";

/** A marketer's page: what the marketer sees, and (with the optional fields) what the team sees. */
export type PartnerPage = {
  affiliate: {
    id?: string; fullName: string; email: string; commissionPercent: number;
    phone?: string | null; active?: boolean; hasPassword?: boolean; lastLoginAt?: string | null; createdAt?: string;
    iban?: string | null; note?: string | null;
  };
  totals: { pendingHalalas: number; paidHalalas: number; earnedHalalas: number; thisMonthHalalas: number; clients: number; payingClients: number };
  clients: PartnerClient[];
  codes: PartnerCode[];
  ledger: LedgerEntry[];
  payouts: { at: string; amountHalalas: number; reference: string | null; note?: string | null }[];
};

export type PartnerClient = {
  officeId?: string; name: string; plan: "STARTER" | "BASIC" | "GROWTH"; cycle: "MONTHLY" | "ANNUAL" | null;
  status: Billing["status"]; paidUntil: string | null; trialEndsAt: string | null; code: string; since: string;
  paidHalalas: number; commissionHalalas: number; visibleToMarketer?: boolean;
};

export type PartnerCode = {
  id?: string; code: string; summary: string; newCustomersOnly: boolean; active: boolean; expiresAt: string | null;
  redemptions: number; maxRedemptions: number | null;
};

export type LedgerEntry = {
  at: string; entry: "EARNED" | "REFUND"; invoiceKind: string; client: string | null; baseHalalas: number; percent: number;
  amountHalalas: number; paid: boolean; paidAt: string | null;
};

export type OpsAffiliate = {
  id: string; fullName: string; email: string; phone: string | null; commissionPercent: number; active: boolean;
  hasPassword: boolean; lastLoginAt: string | null; createdAt: string; codes: number; clients: number;
  pendingHalalas: number; paidHalalas: number;
};

/**
 * 898200 → "8,982 ريال"; a negative amount as "−89.82 ريال". The number is isolated left-to-right so the minus sign
 * stays next to it in Arabic text.
 */
export function riyal(halalas: number): string {
  const abs = Math.abs(halalas) / 100;
  const s = abs.toLocaleString("en-US", { minimumFractionDigits: abs % 1 ? 2 : 0, maximumFractionDigits: 2 });
  return `\u2066${halalas < 0 ? "−" : ""}${s}\u2069 ريال`;
}

export function rate(p: number): string {
  return `${Number(p).toLocaleString("en-US", { maximumFractionDigits: 2 })}٪`;
}

export const KIND_LABELS: Record<string, string> = { SUBSCRIPTION: "اشتراك", OVERAGE: "محادثات إضافية" };
