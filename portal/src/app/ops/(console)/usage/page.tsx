"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { BarList } from "@/components/Charts";
import { api, fmt, SUB_LABELS } from "@/lib/api";
import { day, FUNNEL_LABELS, PLAN_NAMES, sar, type OpsFunnel, type OpsUsage } from "@/lib/ops";

const LOCALE = "ar-SA-u-ca-gregory-nu-latn";
const FUNNEL_PERIODS: [number, string][] = [[30, "آخر 30 يوماً"], [90, "آخر 90 يوماً"], [0, "منذ البداية"]];

function months(): string[] {
  const out: string[] = [];
  const d = new Date();
  for (let i = 0; i < 6; i++) {
    const m = new Date(d.getFullYear(), d.getMonth() - i, 15);
    out.push(`${m.getFullYear()}-${String(m.getMonth() + 1).padStart(2, "0")}`);
  }
  return out;
}

function monthTitle(ym: string) {
  return new Date(`${ym}-15T12:00:00`).toLocaleDateString(LOCALE, { month: "long", year: "numeric" });
}

function statusPill(s: string) {
  if (s === "SUSPENDED") return <span className="pill p-bad">موقوف</span>;
  const l = SUB_LABELS[s as keyof typeof SUB_LABELS];
  return l ? <span className={`pill ${l[1]}`}>{l[0]}</span> : s;
}

export default function OpsUsagePage() {
  const options = useMemo(months, []);
  const [month, setMonth] = useState(options[0]);
  const [usage, setUsage] = useState<OpsUsage | null>(null);
  const [days, setDays] = useState(90);
  const [funnel, setFunnel] = useState<OpsFunnel | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api<OpsUsage>(`/ops/usage?month=${month}`).then(setUsage).catch(() => setError("تعذّر تحميل الاستخدام."));
  }, [month]);
  useEffect(() => {
    api<OpsFunnel>(`/ops/funnel?days=${days}`).then(setFunnel).catch(() => setError("تعذّر تحميل مسار التسجيل."));
  }, [days]);

  if (!usage || !funnel) return <p className="muted">{error || "جارٍ التحميل…"}</p>;
  const t = usage.totals;
  const signed = funnel.steps[0]?.count ?? 0;

  return (
    <>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1>الاستخدام والتكلفة</h1>
        <select value={month} onChange={(e) => setMonth(e.target.value)} aria-label="الشهر" style={{ width: "auto" }}>
          {options.map((m) => <option key={m} value={m}>{monthTitle(m)}</option>)}
        </select>
      </div>

      <section className="kpis" aria-label={`ملخص ${monthTitle(usage.month)}`}>
        <div className="kpi"><b>{fmt(t.conversations)}</b><span>محادثة عميل (تُحسب مرة كل 24 ساعة)</span></div>
        <div className="kpi"><b>{fmt(t.replies)}</b><span>رد من الوكيل{t.errors > 0 ? `، ${fmt(t.errors)} خطأ` : ""}</span></div>
        <div className="kpi"><b>{sar(t.aiCost)}</b><span>ريال تكلفة Gemini التقديرية</span></div>
        <div className="kpi"><b>{t.costPerReply == null ? "—" : `${(t.costPerReply * 100).toFixed(2)}`}</b><span>هللة تكلفة الرد الواحد</span></div>
        <div className="kpi"><b>{sar(t.revenue)}</b><span>ريال إيراد شهري من الاشتراكات</span></div>
        <div className="kpi"><b dir="ltr" style={{ textAlign: "right" }}>{sar(t.margin)}</b><span>ريال الفرق بعد تكلفة الذكاء الاصطناعي</span></div>
      </section>

      <div className="tbl"><table>
        <thead><tr><th>المكتب</th><th>الحالة</th><th>محادثات</th><th>ردود</th><th>أخطاء</th><th>تكلفة الذكاء الاصطناعي</th><th>الإيراد الشهري</th><th>الفرق</th></tr></thead>
        <tbody>
          {usage.offices.length === 0 && <tr><td colSpan={8} className="muted">لا مكاتب.</td></tr>}
          {usage.offices.map((o) => (
            <tr key={o.officeId}>
              <td><Link href={`/ops/offices/${o.officeId}`}>{o.office}</Link><br /><span className="small muted">{PLAN_NAMES[o.plan]}</span></td>
              <td>{statusPill(o.status)}</td>
              <td className="num">{fmt(o.conversations)}</td>
              <td className="num">{fmt(o.replies)}</td>
              <td className="num">{o.errors > 0 ? <span className="pill p-bad">{fmt(o.errors)}</span> : "0"}</td>
              <td className="num">{sar(o.aiCost)}</td>
              <td className="num">{sar(o.revenue)}</td>
              <td className="num" style={{ color: o.margin < 0 ? "var(--bad)" : undefined }}><span dir="ltr">{sar(o.margin)}</span></td>
            </tr>
          ))}
        </tbody>
      </table></div>
      <p className="small muted">
        التكلفة تقديرية: {fmt(t.inputTokens)} وحدة مدخلة و{fmt(t.outputTokens)} وحدة مخرجة بسعر {usage.prices.inputUsdPerMillion}$ و{usage.prices.outputUsdPerMillion}$ للمليون، وصرف {usage.prices.usdToSar} ريال.
        غيّر الأسعار بالمتغيرين LLM_PRICE_INPUT_USD وLLM_PRICE_OUTPUT_USD إذا تغيّر النموذج أو سعره. الإيراد لا يشمل المحادثات الإضافية.
      </p>

      <section className="card" aria-labelledby="funnel-h" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2 id="funnel-h">مسار المكاتب الجديدة</h2>
          <div className="periods" role="group" aria-label="فترة التسجيل">
            {FUNNEL_PERIODS.map(([d, l]) => <button key={d} type="button" aria-pressed={days === d} onClick={() => setDays(d)}>{l}</button>)}
          </div>
        </div>
        <p className="small muted">من المكاتب التي سجّلت في الفترة: كم وصل إلى كل خطوة.</p>
        <BarList label="مسار المكاتب" rows={funnel.steps.map((s) => ({
          name: FUNNEL_LABELS[s.key], value: s.count,
          note: s.key === "SIGNED_UP" ? undefined : <span className="muted">{signed === 0 ? "—" : `${Math.round((s.count / signed) * 100)}٪`}</span>,
        }))} />
        {funnel.stuck.length > 0 && (
          <details>
            <summary className="small">مكاتب لم تكمل ({funnel.stuck.length})</summary>
            <div className="tbl" style={{ marginTop: 8 }}><table>
              <thead><tr><th>المكتب</th><th>الحالة</th><th>سجّل</th><th>الخطوة التالية</th></tr></thead>
              <tbody>
                {funnel.stuck.map((s) => (
                  <tr key={s.officeId}>
                    <td><Link href={`/ops/offices/${s.officeId}`}>{s.office}</Link></td>
                    <td>{statusPill(s.status)}</td>
                    <td className="small">{day(s.createdAt)}</td>
                    <td className="small">{FUNNEL_LABELS[s.next]}</td>
                  </tr>
                ))}
              </tbody>
            </table></div>
          </details>
        )}
      </section>
    </>
  );
}
