"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { api, fmt, SUB_LABELS } from "@/lib/api";
import { day, FAL_LABELS, PLAN_NAMES, type OpsOffice, type OpsOverview } from "@/lib/ops";

type Filter = "ALL" | "TRIAL" | "ACTIVE" | "PAST_DUE" | "EXPIRED" | "SUSPENDED" | "FAL";

const FILTERS: [Filter, string][] = [
  ["ALL", "الكل"], ["TRIAL", "تجربة"], ["ACTIVE", "مشترك"], ["PAST_DUE", "بانتظار التجديد"], ["EXPIRED", "متوقف"],
  ["SUSPENDED", "موقوف"], ["FAL", "فال قيد التحقق"],
];

export default function OpsOffices() {
  const [overview, setOverview] = useState<OpsOverview | null>(null);
  const [offices, setOffices] = useState<OpsOffice[] | null>(null);
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState<Filter>("ALL");
  const [error, setError] = useState("");

  useEffect(() => {
    Promise.all([api<OpsOverview>("/ops/overview"), api<OpsOffice[]>("/ops/offices")])
      .then(([o, list]) => { setOverview(o); setOffices(list); })
      .catch(() => setError("تعذّر تحميل المكاتب."));
  }, []);

  const shown = useMemo(() => (offices ?? []).filter((o) => {
    const text = `${o.name} ${o.ownerEmail ?? ""} ${o.ownerPhone ?? ""} ${o.falLicense}`.toLowerCase();
    if (q && !text.includes(q.trim().toLowerCase())) return false;
    if (filter === "SUSPENDED") return o.suspended;
    if (filter === "FAL") return o.falStatus === "PENDING_REVIEW";
    return filter === "ALL" || o.subscription === filter;
  }), [offices, q, filter]);

  if (!overview || !offices) return <p className="muted">{error || "جارٍ التحميل…"}</p>;

  return (
    <>
      <h1>المكاتب</h1>
      {!overview.mailConfigured && (
        <p className="card small" role="status">البريد غير مضبوط (MAIL_HOST): رسائل الدعم وروابط استعادة كلمة المرور لا تُرسل بالبريد بعد.</p>
      )}
      <section className="kpis" aria-label="ملخص">
        <div className="kpi"><b>{fmt(overview.offices)}</b><span>مكتب مسجّل، {overview.whatsappConnected} ربط واتساب</span></div>
        <div className="kpi"><b>{fmt(overview.byStatus.ACTIVE)}</b><span>مشترك، {overview.byStatus.TRIAL} في التجربة</span></div>
        <div className="kpi"><b>{fmt(overview.mrrSar)}</b><span>ريال إيراد شهري تقديري (قبل الخصومات والضريبة)</span></div>
        <div className="kpi"><b>{overview.falPending}</b><span>رخصة فال بانتظار التحقق</span></div>
        <div className="kpi"><b>{overview.byStatus.PAST_DUE + overview.byStatus.EXPIRED}</b><span>بانتظار التجديد أو متوقف، {overview.suspended} موقوف</span></div>
        <div className="kpi"><b>{fmt(overview.conversationsThisMonth)}</b><span>محادثة هذا الشهر</span></div>
      </section>

      <div className="card" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <input type="search" placeholder="ابحث بالاسم أو البريد أو الجوال أو رقم فال" value={q} onChange={(e) => setQ(e.target.value)} aria-label="بحث" />
        <div className="row" role="radiogroup" aria-label="تصفية">
          {FILTERS.map(([f, label]) => (
            <button key={f} type="button" role="radio" aria-checked={filter === f} className={`btn${filter === f ? " primary" : ""}`} onClick={() => setFilter(f)}>{label}</button>
          ))}
        </div>
      </div>

      <div className="tbl"><table>
        <thead><tr><th>المكتب</th><th>الحالة</th><th>الباقة</th><th>فال</th><th>واتساب</th><th>محادثات الشهر</th><th>آخر دخول</th><th>التسجيل</th></tr></thead>
        <tbody>
          {shown.length === 0 && <tr><td colSpan={8} className="muted">لا مكاتب مطابقة.</td></tr>}
          {shown.map((o) => {
            const [label, cls] = SUB_LABELS[o.subscription];
            const [falLabel, falCls] = FAL_LABELS[o.falStatus];
            return (
              <tr key={o.id}>
                <td><Link href={`/ops/offices/${o.id}`}><b>{o.name}</b></Link><br /><span className="small muted" dir="ltr">{o.ownerEmail}</span></td>
                <td>{o.suspended ? <span className="pill p-bad">موقوف</span> : <span className={`pill ${cls}`}>{label}</span>}
                  {o.subscription === "TRIAL" && <><br /><span className="small muted">حتى {day(o.trialEndsAt)}</span></>}
                  {o.paidUntil && o.subscription !== "TRIAL" && <><br /><span className="small muted">حتى {day(o.paidUntil)}</span></>}
                </td>
                <td>{PLAN_NAMES[o.plan]}{o.cycle === "ANNUAL" ? " (سنوي)" : ""}{o.promoCode && <><br /><span className="small muted" dir="ltr">{o.promoCode}</span></>}</td>
                <td><span className={`pill ${falCls}`}>{falLabel}</span></td>
                <td>{o.whatsappConnected ? "مربوط" : "—"}</td>
                <td>{fmt(o.conversationsThisMonth)}</td>
                <td className="small">{day(o.lastLoginAt)}</td>
                <td className="small">{day(o.createdAt)}</td>
              </tr>
            );
          })}
        </tbody>
      </table></div>
    </>
  );
}
