"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { ColumnChart, type ChartDatum } from "@/components/Charts";
import { api, ApiError } from "@/lib/api";
import { day, sar, type OpsInvoice, type OpsRevenue } from "@/lib/ops";

type Filter = "ALL" | "DUE" | "PAID" | "VOID";
const FILTERS: [Filter, string][] = [["ALL", "الكل"], ["DUE", "مستحقة"], ["PAID", "مدفوعة"], ["VOID", "ملغاة"]];
const KIND: Record<OpsInvoice["kind"], string> = { SUBSCRIPTION: "اشتراك", OVERAGE: "محادثات إضافية", SETUP: "إعداد", COMP: "مجاني" };
const LOCALE = "ar-SA-u-ca-gregory-nu-latn";

function monthTitle(ym: string) {
  return new Date(`${ym}-15T12:00:00`).toLocaleDateString(LOCALE, { month: "long", year: "numeric" });
}

function monthLabel(ym: string) {
  return new Date(`${ym}-15T12:00:00`).toLocaleDateString(LOCALE, { month: "short" });
}

function StatusPill({ s }: { s: OpsInvoice["status"] }) {
  return s === "PAID" ? <span className="pill p-ok">مدفوعة</span> : s === "DUE" ? <span className="pill p-warn">مستحقة</span> : <span className="pill p-idle">ملغاة</span>;
}

export default function OpsRevenuePage() {
  const [data, setData] = useState<OpsRevenue | null>(null);
  const [error, setError] = useState("");
  const [filter, setFilter] = useState<Filter>("ALL");
  const [q, setQ] = useState("");
  const [paying, setPaying] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");

  const load = useCallback(() => api<OpsRevenue>("/ops/revenue").then(setData).catch(() => setError("تعذّر تحميل الإيرادات.")), []);
  useEffect(() => { void load(); }, [load]);

  const shown = useMemo(() => (data?.invoices ?? []).filter((i) => {
    if (filter !== "ALL" && i.status !== filter) return false;
    const text = `${i.office} ${i.number} ${i.description} ${i.promoCode ?? ""}`.toLowerCase();
    return !q || text.includes(q.trim().toLowerCase());
  }), [data, filter, q]);

  async function markPaid(inv: OpsInvoice) {
    if (!note.trim()) { setMsg("اكتب مرجع التحويل أو ملاحظة للسجل."); return; }
    setBusy(true);
    setMsg("");
    try {
      await api(`/ops/invoices/${inv.id}/paid`, { method: "POST", json: { officeId: inv.officeId, note } });
      setPaying(null);
      setNote("");
      setMsg(`سُجّلت الفاتورة ${inv.number} مدفوعة، وفُعّل ما تشمله.`);
      await load();
    } catch (e) {
      setMsg(e instanceof ApiError ? e.message : "تعذّر تسجيل الدفعة.");
    } finally {
      setBusy(false);
    }
  }

  if (!data) return <p className="muted">{error || "جارٍ التحميل…"}</p>;
  const thisMonth = data.months[data.months.length - 1];
  const lastMonth = data.months[data.months.length - 2];

  return (
    <>
      <h1>الإيرادات والفواتير</h1>
      <section className="kpis" aria-label="ملخص الإيرادات">
        <div className="kpi"><b>{sar(data.mrr)}</b><span>ريال إيراد شهري متكرر (قبل الضريبة والخصومات)</span></div>
        <div className="kpi"><b>{sar(data.arr)}</b><span>ريال سنوياً بهذا المعدل</span></div>
        <div className="kpi"><b>{data.payingOffices}</b><span>مكتب مشترك ويدفع</span></div>
        <div className="kpi"><b>{sar(thisMonth?.total)}</b><span>ريال حُصّل هذا الشهر، {sar(lastMonth?.total)} الشهر الماضي</span></div>
        <button type="button" className="kpi" style={{ textAlign: "inherit", cursor: "pointer" }} onClick={() => setFilter("DUE")}>
          <b>{sar(data.unpaidTotal)}</b><span>ريال غير مدفوع في {data.unpaidCount} فاتورة</span>
        </button>
      </section>

      <section className="card" aria-labelledby="months-h">
        <h2 id="months-h">المحصّل شهرياً</h2>
        <p className="small muted">الفواتير المدفوعة حسب شهر الدفع، بدون ضريبة القيمة المضافة.</p>
        <ColumnChart
          label="الإيراد المحصّل شهرياً بالريال"
          format={sar}
          series={[
            { key: "subscriptions", label: "اشتراكات", color: "var(--viz-1)" },
            { key: "overage", label: "محادثات إضافية", color: "var(--viz-2)" },
            { key: "setup", label: "إعداد", color: "var(--viz-3)" },
          ]}
          data={data.months.map((m): ChartDatum => ({ label: monthLabel(m.month), title: monthTitle(m.month),
            values: { subscriptions: m.subscriptions, overage: m.overage, setup: m.setup } }))}
        />
      </section>

      <div className="card" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <input type="search" placeholder="ابحث باسم المكتب أو رقم الفاتورة أو الكود" value={q} onChange={(e) => setQ(e.target.value)} aria-label="بحث" />
        <div className="row" role="radiogroup" aria-label="تصفية الفواتير">
          {FILTERS.map(([f, label]) => (
            <button key={f} type="button" role="radio" aria-checked={filter === f} className={`btn${filter === f ? " primary" : ""}`} onClick={() => setFilter(f)}>{label}</button>
          ))}
        </div>
        {msg && <p className="small" role="status" style={{ margin: 0 }}>{msg}</p>}
      </div>

      <div className="tbl"><table>
        <thead><tr><th>الفاتورة</th><th>المكتب</th><th>البيان</th><th>الإجمالي</th><th>الحالة</th><th>التاريخ</th><th /></tr></thead>
        <tbody>
          {shown.length === 0 && <tr><td colSpan={7} className="muted">لا فواتير مطابقة.</td></tr>}
          {shown.map((i) => (
            <tr key={i.id}>
              <td><span className="mono">{i.number}</span><br /><span className="small muted">{KIND[i.kind]}{i.provider === "MANUAL" ? " · دفعة يدوية" : ""}</span></td>
              <td><Link href={`/ops/offices/${i.officeId}`}>{i.office}</Link></td>
              <td className="small">{i.description}{i.discount > 0 && <><br /><span className="muted">خصم {sar(i.discount)}{i.promoCode ? ` (${i.promoCode})` : ""}</span></>}</td>
              <td className="num">{sar(i.total)}<br /><span className="small muted">منها ضريبة {sar(i.vat)}</span></td>
              <td><StatusPill s={i.status} />{i.test && <><br /><span className="pill p-acc">تجريبية</span></>}</td>
              <td className="small">{day(i.createdAt)}{i.paidAt && <><br /><span className="muted">دُفعت {day(i.paidAt)}</span></>}</td>
              <td>
                {i.status !== "PAID" && i.kind !== "COMP" && (paying === i.id ? (
                  <div style={{ display: "flex", flexDirection: "column", gap: 6, minWidth: 220 }}>
                    <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="مرجع التحويل البنكي" aria-label="مرجع التحويل" />
                    <div className="row">
                      <button type="button" className="btn primary" disabled={busy} onClick={() => void markPaid(i)}>{busy ? "جارٍ…" : "تأكيد الدفع"}</button>
                      <button type="button" className="btn" onClick={() => { setPaying(null); setNote(""); }}>إلغاء</button>
                    </div>
                  </div>
                ) : (
                  <button type="button" className="btn" style={{ whiteSpace: "nowrap" }} onClick={() => { setPaying(i.id); setNote(""); setMsg(""); }}>تسجيل دفعة</button>
                ))}
              </td>
            </tr>
          ))}
        </tbody>
      </table></div>
      <p className="small muted">الفواتير «التجريبية» لحسابات فريق حداء الداخلية (بريد مؤكد على نطاق hidaaco.com): تُدفع ببطاقات اختبار Moyasar ولا تدخل في الإيرادات.</p>
      <p className="small muted">«تسجيل دفعة» لما يُدفع خارج صفحة الدفع (تحويل بنكي): تصبح الفاتورة مدفوعة ويُفعّل الاشتراك كأنه دُفع إلكترونياً، ويُحفظ المرجع في سجل العمليات.</p>
    </>
  );
}
