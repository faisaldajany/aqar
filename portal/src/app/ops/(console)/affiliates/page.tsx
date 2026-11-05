"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { when } from "@/lib/ops";
import { rate, riyal, type OpsAffiliate } from "@/lib/partner";

/** Hidaa's marketers: add one (they get an invitation), and see each one's clients and what they are owed. */
export default function OpsAffiliates() {
  const [rows, setRows] = useState<OpsAffiliate[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ ok: boolean; text: string; link?: string } | null>(null);

  const load = useCallback(async () => setRows(await api<OpsAffiliate[]>("/ops/affiliates")), []);
  useEffect(() => {
    load().catch(() => setNotice({ ok: false, text: "تعذّر التحميل." }));
  }, [load]);

  async function create(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const f = new FormData(form);
    const text = (k: string) => String(f.get(k) ?? "").trim() || null;
    setBusy(true);
    setNotice(null);
    try {
      const r = await api<{ id: string; inviteUrl: string; emailed: boolean }>("/ops/affiliates", {
        method: "POST",
        json: { fullName: text("fullName"), email: text("email"), phone: text("phone"), commissionPercent: Number(f.get("rate")),
          iban: text("iban"), note: text("note") },
      });
      setNotice({ ok: true, text: r.emailed ? "أُضيف المسوّق وأُرسلت له الدعوة بالبريد. الرابط أيضاً:" : "أُضيف المسوّق. البريد غير مضبوط، فأرسل له هذا الرابط بنفسك (صالح 7 أيام):", link: r.inviteUrl });
      form.reset();
      await load();
    } catch (err) {
      setNotice({ ok: false, text: err instanceof ApiError ? err.message : "تعذّر إضافة المسوّق." });
    } finally {
      setBusy(false);
    }
  }

  const pending = rows?.reduce((s, r) => s + Math.max(0, r.pendingHalalas), 0) ?? 0;

  return (
    <>
      <h1>المسوّقون بالعمولة</h1>
      <details className="card">
        <summary><b>كيف يعمل</b></summary>
        <ul className="small" style={{ marginBottom: 0 }}>
          <li>أضف المسوّق ونسبة عمولته، فتصله دعوة لإنشاء كلمة مرور لبوابة المسوّقين (<span dir="ltr">/partner</span>).</li>
          <li>أنشئ له كوداً من صفحته (أو من أكواد الخصم باختيار المسوّق). المكتب الذي يطبّق الكود يصبح عميلاً له.</li>
          <li>كل دفعة اشتراك أو محادثات إضافية من عميله تعطيه نسبته مما دفعه المكتب قبل الضريبة، ما دام الكود فعّالاً. الدفع التجريبي والأشهر المجانية بلا عمولة.</li>
          <li>الاسترداد يخصم نصيب العمولة نفسه، حتى بعد التحويل (يُخصم من التحويل القادم).</li>
          <li>يرى المسوّق اسم المكتب وباقته وحالته وتاريخ التجديد والمبالغ، ما دام الكود فعّالاً ومستخدماً. إيقاف الكود أو انتقال المكتب لكود آخر يخفي المكتب عنه ويوقف العمولة.</li>
          <li>سجّل التحويل البنكي من صفحة المسوّق، فتُسوّى كل عمولاته المستحقة.</li>
        </ul>
      </details>

      <form className="card" onSubmit={create} style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <h2>مسوّق جديد</h2>
        <div className="grid3">
          <label className="f" htmlFor="fullName">الاسم<input id="fullName" name="fullName" required maxLength={100} /></label>
          <label className="f" htmlFor="email">البريد الإلكتروني<input id="email" name="email" type="email" dir="ltr" required maxLength={200} /></label>
          <label className="f" htmlFor="rate">نسبة العمولة ٪<input id="rate" name="rate" type="number" required min={0.01} max={100} step="0.01" defaultValue={20} /></label>
        </div>
        <div className="grid3">
          <label className="f" htmlFor="phone">الجوال (اختياري)<input id="phone" name="phone" type="tel" dir="ltr" maxLength={30} /></label>
          <label className="f" htmlFor="iban">الآيبان للتحويل (اختياري)<input id="iban" name="iban" dir="ltr" maxLength={40} placeholder="SA…" /></label>
          <label className="f" htmlFor="note">ملاحظة داخلية<input id="note" name="note" maxLength={300} /></label>
        </div>
        {notice && (
          <div className={notice.ok ? "small" : "err"} role={notice.ok ? "status" : "alert"}>
            {notice.text}
            {notice.link && <div className="row" style={{ marginTop: 6 }}><code dir="ltr" style={{ wordBreak: "break-all" }}>{notice.link}</code>
              <button type="button" className="btn" onClick={() => void navigator.clipboard?.writeText(notice.link!)}>نسخ</button></div>}
          </div>
        )}
        <div><button className="btn primary" disabled={busy}>{busy ? "جارٍ الإضافة…" : "أضف وأرسل الدعوة"}</button></div>
      </form>

      {rows && rows.length > 0 && <p className="small muted">المستحق لكل المسوّقين الآن: <b>{riyal(pending)}</b></p>}
      <div className="tbl"><table>
        <thead><tr><th>المسوّق</th><th>العمولة</th><th>الأكواد</th><th>العملاء الحاليون</th><th>مستحق</th><th>حُوِّل</th><th>الدخول</th><th>الحالة</th></tr></thead>
        <tbody>
          {rows == null && <tr><td colSpan={8} className="muted">جارٍ التحميل…</td></tr>}
          {rows?.length === 0 && <tr><td colSpan={8} className="muted">لا مسوّقين بعد.</td></tr>}
          {rows?.map((a) => (
            <tr key={a.id}>
              <td><Link href={`/ops/affiliates/${a.id}`}><b>{a.fullName}</b></Link><br /><span className="small muted" dir="ltr">{a.email}</span></td>
              <td>{rate(a.commissionPercent)}</td>
              <td>{a.codes}</td>
              <td>{a.clients}</td>
              <td className="num"><b>{riyal(a.pendingHalalas)}</b></td>
              <td className="num">{riyal(a.paidHalalas)}</td>
              <td className="small">{!a.hasPassword ? <span className="pill p-warn">لم يقبل الدعوة</span> : a.lastLoginAt ? when(a.lastLoginAt) : "—"}</td>
              <td>{a.active ? <span className="pill p-ok">فعّال</span> : <span className="pill p-idle">موقوف</span>}</td>
            </tr>
          ))}
        </tbody>
      </table></div>
    </>
  );
}
