"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useState } from "react";
import { api, ApiError, fmt, riyadhDay, sar, SUB_LABELS, type Billing, type Invoice } from "@/lib/api";

export default function BillingPage() {
  return <Suspense fallback={null}><BillingView /></Suspense>;
}

function BillingView() {
  const params = useSearchParams();
  const [b, setB] = useState<Billing | null>(null);
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [cycle, setCycle] = useState<"MONTHLY" | "ANNUAL">("MONTHLY");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");

  const load = useCallback(async () => {
    const [x, inv] = await Promise.all([api<Billing>("/billing"), api<Invoice[]>("/billing/invoices")]);
    setB(x);
    setInvoices(inv);
  }, []);

  useEffect(() => {
    const returned = params.get("invoice");
    (returned ? api(`/billing/invoices/${returned}/confirm`, { method: "POST" }).catch(() => undefined) : Promise.resolve())
      .then(load).catch(() => setError("تعذّر التحميل."));
  }, [load, params]);

  async function choose(plan: string) {
    setBusy(plan);
    setError("");
    try {
      const inv = await api<Invoice>("/billing/checkout", { method: "POST", json: { plan, cycle } });
      if (inv.payUrl) window.location.href = inv.payUrl;
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر فتح صفحة الدفع.");
    } finally {
      setBusy("");
    }
  }

  if (!b) return <p className="muted">{error || "جارٍ التحميل…"}</p>;
  const [label, cls] = SUB_LABELS[b.status];

  return (
    <>
      <h1>الاشتراك</h1>
      <div className="card">
        <p><span className={`pill ${cls}`}>{label}</span>{" "}
          {b.status === "TRIAL" && <>باقي {b.trialDaysLeft} يوم من التجربة المجانية (تنتهي {riyadhDay(b.trialEndsAt)}). اشترك الآن ولن تخسر أيام التجربة المتبقية.</>}
          {b.status === "ACTIVE" && b.paidUntil && <>باقة {b.plans.find((p) => p.id === b.plan)?.name} · يتجدد في {riyadhDay(b.paidUntil)}</>}
          {b.status === "PAST_DUE" && <>فاتورة التجديد بانتظار الدفع. يتوقف الوكيل إن لم تُدفع خلال أيام.</>}
          {b.status === "EXPIRED" && <>الوكيل متوقف ولا يرد على العملاء. اختر باقة ليعود فوراً.</>}
        </p>
      </div>

      <div className="row" role="radiogroup" aria-label="طريقة الدفع">
        <button className={`btn${cycle === "MONTHLY" ? " primary" : ""}`} role="radio" aria-checked={cycle === "MONTHLY"} onClick={() => setCycle("MONTHLY")}>شهري</button>
        <button className={`btn${cycle === "ANNUAL" ? " primary" : ""}`} role="radio" aria-checked={cycle === "ANNUAL"} onClick={() => setCycle("ANNUAL")}>سنوي (شهران مجاناً)</button>
      </div>
      {error && <p className="err" role="alert">{error}</p>}
      <div className="grid3" style={{ marginTop: 12 }}>
        {b.plans.map((p) => {
          const price = cycle === "ANNUAL" ? p.annualSar : p.monthlySar;
          const current = b.plan === p.id && b.status === "ACTIVE" && b.cycle === cycle;
          return (
            <div key={p.id} className="card" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
              <h2>{p.name}</h2>
              <p><b style={{ fontSize: 28 }}>{fmt(price)}</b> ريال {cycle === "ANNUAL" ? "سنوياً" : "شهرياً"} <span className="small muted">+ ضريبة {b.vatPercent}٪</span></p>
              <ul className="small" style={{ margin: 0, paddingInlineStart: 18 }}>
                <li>{fmt(p.includedConversations)} محادثة بالشهر</li>
                <li>بعد الحد: {p.overageSar == null ? "يحول لك المحادثات الجديدة" : `${p.overageSar} ريال للمحادثة`}</li>
                <li>{p.marketers === 1 ? "موظف واحد يستلم العملاء" : `حتى ${p.marketers} موظفين يستلمون العملاء`}</li>
                <li>حجز المعاينة والتذكير، وتقرير يومي على جوالك</li>
                <li>بلا رسوم تأسيس وبلا عقد</li>
              </ul>
              <button className="btn primary" disabled={!!busy || current} onClick={() => choose(p.id)}>
                {current ? "باقتك الحالية" : busy === p.id ? "جارٍ فتح صفحة الدفع…" : "اشترك"}
              </button>
            </div>
          );
        })}
      </div>
      <p className="small muted">المحادثة: كل عميل يرد عليه حداء خلال 24 ساعة تُحسب محادثة واحدة مهما كان عدد الرسائل.</p>
      <p className="small muted">الدفع عبر صفحة ميسّر الآمنة (مدى، فيزا، ماستركارد، Apple Pay). لا نحفظ بيانات بطاقتك.</p>

      <div className="card">
        <h2>الفواتير</h2>
        {invoices.length === 0 ? <p className="small muted">لا فواتير بعد.</p> : (
          <div className="tbl"><table>
            <thead><tr><th>الرقم</th><th>الوصف</th><th>المبلغ شامل الضريبة</th><th>الحالة</th><th /></tr></thead>
            <tbody>
              {invoices.map((i) => (
                <tr key={i.id}>
                  <td className="mono small">{i.number}</td>
                  <td className="small">{i.description}</td>
                  <td>{sar(i.totalHalalas)} ريال</td>
                  <td>{i.status === "PAID" ? <span className="pill p-ok">مدفوعة</span> : i.status === "DUE" ? <span className="pill p-warn">مستحقة</span> : <span className="pill p-idle">ملغاة</span>}</td>
                  <td>{i.status === "DUE" && i.payUrl && <a className="btn" href={i.payUrl}>ادفع</a>}</td>
                </tr>
              ))}
            </tbody>
          </table></div>
        )}
      </div>
    </>
  );
}
