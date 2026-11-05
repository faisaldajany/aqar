"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { SUB_LABELS } from "@/lib/api";
import { day, PLAN_NAMES } from "@/lib/ops";
import { KIND_LABELS, rate, riyal, type PartnerPage } from "@/lib/partner";

/**
 * A marketer's figures: earnings, codes, clients, the commission ledger and payouts. The marketer sees it on their own
 * page; the team sees the same with former clients and links to the offices ({@code team}).
 */
export function PartnerView({ page, team = false }: { page: PartnerPage; team?: boolean }) {
  const { totals, clients, codes, ledger, payouts } = page;
  const [origin, setOrigin] = useState("https://app.hidaaco.com");
  const [copied, setCopied] = useState("");
  useEffect(() => setOrigin(window.location.origin), []);

  async function copy(code: string) {
    await navigator.clipboard?.writeText(`${origin}/signup?promo=${code}`).catch(() => undefined);
    setCopied(code);
    window.setTimeout(() => setCopied(""), 2000);
  }

  return (
    <>
      <section className="kpis k4" aria-label="الأرباح">
        <div className="kpi"><b>{riyal(totals.pendingHalalas)}</b><span>{team ? "مستحق للمسوّق، لم يُحوَّل بعد" : "مستحق لك، يُحوَّل في الدفعة القادمة"}</span></div>
        <div className="kpi"><b>{riyal(totals.paidHalalas)}</b><span>{team ? "حُوِّل للمسوّق" : "حُوِّل لك حتى الآن"}</span></div>
        <div className="kpi"><b>{riyal(totals.thisMonthHalalas)}</b><span>عمولة هذا الشهر</span></div>
        <div className="kpi"><b>{totals.clients}</b><span>{team ? "عميل حالي" : "عميل حالي لك"}، {totals.payingClients} منهم مشترك</span></div>
      </section>

      <p className="small muted">
        العمولة {rate(page.affiliate.commissionPercent)} مما يدفعه المكتب قبل الضريبة، على كل دفعة اشتراك أو محادثات إضافية
        تتم {team ? "بكود المسوّق" : "بكودك"} ما دام الكود فعّالاً. الاسترداد يخصم نصيبه من العمولة.
        {!team && " ترى المكتب هنا ما دام كودك فعّالاً ومستخدماً في اشتراكه."}
      </p>

      <section aria-labelledby="codes-h">
        <h2 id="codes-h">{team ? "الأكواد" : "أكوادك"}</h2>
        {codes.length === 0 ? <p className="muted small">{team ? "لا أكواد بعد." : "لا أكواد بعد. يرسلها لك فريق حداء."}</p> : (
          <div className="tbl pv"><table>
            <thead><tr><th>الكود</th><th>ما يحصل عليه المكتب</th><th>الاستخدام</th><th>الحالة</th><th /></tr></thead>
            <tbody>{codes.map((c) => (
              <tr key={c.code}>
                <td><b dir="ltr">{c.code}</b></td>
                <td className="small">{c.summary}{c.newCustomersOnly ? " · للمكاتب الجديدة" : ""}{c.expiresOn ? ` · حتى ${day(c.expiresOn)}` : ""}</td>
                <td>{c.redemptions}{c.maxRedemptions ? ` / ${c.maxRedemptions}` : ""}</td>
                <td>{c.active ? <span className="pill p-ok">فعّال</span> : <span className="pill p-idle">موقوف</span>}</td>
                <td><button type="button" className="btn" onClick={() => void copy(c.code)}>{copied === c.code ? "نُسخ" : "نسخ رابط التسجيل"}</button></td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>

      <section aria-labelledby="clients-h">
        <h2 id="clients-h">{team ? "العملاء" : "عملاؤك"}</h2>
        {clients.length === 0 ? (
          <p className="muted small">{team ? "لا عملاء بعد." : "لا عملاء بعد. حين يسجّل مكتب بكودك يظهر هنا."}</p>
        ) : (
          <div className="tbl pv"><table>
            <thead><tr><th>المكتب</th><th>الحالة</th><th>الباقة</th><th>التجديد</th><th>دفع بالكود</th><th>العمولة</th></tr></thead>
            <tbody>{clients.map((c, i) => {
              const [label, cls] = SUB_LABELS[c.status] ?? [c.status, "p-idle"];
              return (
                <tr key={c.officeId ?? i}>
                  <td>
                    {team && c.officeId ? <Link href={`/ops/offices/${c.officeId}`}><b>{c.name}</b></Link> : <b>{c.name}</b>}
                    {team && c.visibleToMarketer === false && <> <span className="pill p-idle">الكود موقوف</span></>}
                    <br /><span className="small muted">بالكود <bdi dir="ltr">{c.code}</bdi> منذ {day(c.since)}</span>
                  </td>
                  <td><span className={`pill ${cls}`}>{label}</span></td>
                  <td>{PLAN_NAMES[c.plan] ?? c.plan}{c.cycle && <span className="small muted"> · {c.cycle === "ANNUAL" ? "سنوي" : "شهري"}</span>}</td>
                  <td className="small nw">{c.status === "TRIAL" ? `تنتهي التجربة ${day(c.trialEndsAt)}` : c.paidUntil ? day(c.paidUntil) : "—"}</td>
                  <td className="num">{riyal(c.paidHalalas)}</td>
                  <td className="num"><b>{riyal(c.commissionHalalas)}</b></td>
                </tr>
              );
            })}</tbody>
          </table></div>
        )}
      </section>

      <section aria-labelledby="ledger-h">
        <h2 id="ledger-h">سجل العمولات</h2>
        {ledger.length === 0 ? <p className="muted small">لا عمولات بعد. تُسجَّل هنا عند كل دفعة.</p> : (
          <div className="tbl pv"><table>
            <thead><tr><th>التاريخ</th><th>العميل</th><th>البيان</th><th>دفع المكتب</th><th>العمولة</th><th>الحالة</th></tr></thead>
            <tbody>{ledger.map((e, i) => (
              <tr key={i}>
                <td className="small nw">{day(e.at)}</td>
                <td>{e.client ?? <span className="muted">عميل سابق</span>}</td>
                <td className="small">{e.entry === "REFUND" ? "استرداد" : KIND_LABELS[e.invoiceKind] ?? e.invoiceKind} · {rate(e.percent)}</td>
                <td className="num">{riyal(e.baseHalalas)}</td>
                <td className="num"><b>{riyal(e.amountHalalas)}</b></td>
                <td>{e.paid ? <span className="pill p-ok">حُوِّل {day(e.paidAt)}</span> : <span className="pill p-warn">مستحق</span>}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>

      <section aria-labelledby="payouts-h">
        <h2 id="payouts-h">التحويلات</h2>
        {payouts.length === 0 ? <p className="muted small">لا تحويلات بعد.</p> : (
          <div className="tbl pv"><table>
            <thead><tr><th>التاريخ</th><th>المبلغ</th><th>المرجع</th>{team && <th>ملاحظة</th>}</tr></thead>
            <tbody>{payouts.map((p, i) => (
              <tr key={i}>
                <td className="small nw">{day(p.at)}</td>
                <td className="num"><b>{riyal(p.amountHalalas)}</b></td>
                <td><bdi dir="ltr">{p.reference ?? "—"}</bdi></td>
                {team && <td className="small">{p.note ?? ""}</td>}
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
    </>
  );
}
