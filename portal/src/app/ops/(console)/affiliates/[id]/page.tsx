"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { PartnerView } from "@/components/PartnerView";
import { api, ApiError } from "@/lib/api";
import { when } from "@/lib/ops";
import { riyal, type PartnerPage } from "@/lib/partner";

/** One marketer, as the team sees them: settings, invitation, payouts, and everything the marketer sees. */
export default function OpsAffiliate() {
  const { id } = useParams<{ id: string }>();
  const [page, setPage] = useState<PartnerPage | null>(null);
  const [busy, setBusy] = useState("");
  const [notice, setNotice] = useState<{ ok: boolean; text: string; link?: string } | null>(null);

  const load = useCallback(async () => setPage(await api<PartnerPage>(`/ops/affiliates/${id}`)), [id]);
  useEffect(() => {
    load().catch(() => setNotice({ ok: false, text: "تعذّر التحميل." }));
  }, [load]);

  async function run(key: string, fn: () => Promise<void>) {
    setBusy(key);
    setNotice(null);
    try {
      await fn();
    } catch (err) {
      setNotice({ ok: false, text: err instanceof ApiError ? err.message : "تعذّر التنفيذ." });
    } finally {
      setBusy("");
    }
  }

  function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    void run("save", async () => {
      setPage(await api<PartnerPage>(`/ops/affiliates/${id}`, {
        method: "PATCH",
        json: { fullName: String(f.get("fullName") ?? ""), phone: String(f.get("phone") ?? ""), commissionPercent: Number(f.get("rate")),
          iban: String(f.get("iban") ?? ""), note: String(f.get("note") ?? "") },
      }));
      setNotice({ ok: true, text: "حُفظ. النسبة الجديدة تسري على الدفعات القادمة فقط." });
    });
  }

  function toggle() {
    if (!page) return;
    void run("toggle", async () => {
      setPage(await api<PartnerPage>(`/ops/affiliates/${id}`, { method: "PATCH", json: { active: !page.affiliate.active } }));
    });
  }

  function invite() {
    void run("invite", async () => {
      const r = await api<{ url: string; emailed: boolean }>(`/ops/affiliates/${id}/invite`, { method: "POST" });
      setNotice({ ok: true, text: r.emailed ? "أُرسل رابط كلمة المرور بالبريد. الرابط أيضاً:" : "البريد غير مضبوط. أرسل هذا الرابط للمسوّق (صالح 7 أيام):", link: r.url });
    });
  }

  function payout(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const form = e.currentTarget;
    void run("payout", async () => {
      const r = await api<{ amountHalalas: number }>(`/ops/affiliates/${id}/payouts`, {
        method: "POST", json: { reference: String(f.get("reference") ?? "").trim() || null, note: String(f.get("note") ?? "").trim() || null },
      });
      form.reset();
      setNotice({ ok: true, text: `سُجّل تحويل ${riyal(r.amountHalalas)}.` });
      await load();
    });
  }

  if (!page) return <p className="muted">{notice?.text ?? "جارٍ التحميل…"}</p>;
  const a = page.affiliate;
  const due = page.totals.pendingHalalas;

  return (
    <>
      <p className="small"><Link href="/ops/affiliates">← المسوّقون</Link></p>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1>{a.fullName} {a.active ? <span className="pill p-ok">فعّال</span> : <span className="pill p-idle">موقوف</span>}</h1>
        <div className="row">
          <Link className="btn primary" href={`/ops/promos?affiliate=${id}`}>كود جديد لهذا المسوّق</Link>
          <button type="button" className="btn" disabled={busy === "invite" || !a.active} onClick={invite}>{a.hasPassword ? "رابط كلمة مرور جديدة" : "أعد إرسال الدعوة"}</button>
          <button type="button" className="btn" disabled={busy === "toggle"} onClick={toggle}>{a.active ? "إيقاف المسوّق" : "تفعيل المسوّق"}</button>
        </div>
      </div>
      <p className="small muted">
        <bdi dir="ltr">{a.email}</bdi>{a.phone && <> · <bdi dir="ltr">{a.phone}</bdi></>} · أُضيف {when(a.createdAt)} ·{" "}
        {a.hasPassword ? `آخر دخول: ${when(a.lastLoginAt)}` : "لم يقبل الدعوة بعد"}
        {!a.active && " · الموقوف لا يدخل البوابة ولا يكسب عمولة جديدة"}
      </p>
      {notice && (
        <div className={notice.ok ? "card small" : "err"} role={notice.ok ? "status" : "alert"}>
          {notice.text}
          {notice.link && <div className="row" style={{ marginTop: 6 }}><code dir="ltr" style={{ wordBreak: "break-all" }}>{notice.link}</code>
            <button type="button" className="btn" onClick={() => void navigator.clipboard?.writeText(notice.link!)}>نسخ</button></div>}
        </div>
      )}

      <div className="grid2">
        <form className="card" onSubmit={payout} style={{ display: "flex", flexDirection: "column", gap: 10, alignSelf: "stretch" }}>
          <h2>تسجيل تحويل</h2>
          <p className="small">المستحق الآن: <b>{riyal(due)}</b>{a.iban && <> · الآيبان <bdi dir="ltr">{a.iban}</bdi></>}</p>
          <label className="f" htmlFor="reference">مرجع التحويل البنكي<input id="reference" name="reference" dir="ltr" maxLength={100} /></label>
          <label className="f" htmlFor="pnote">ملاحظة<input id="pnote" name="note" maxLength={300} /></label>
          <div><button className="btn primary" disabled={busy === "payout" || due <= 0}>{due > 0 ? `حوّلت ${riyal(due)}` : "لا مبلغ مستحق"}</button></div>
        </form>
        <form className="card" onSubmit={save} style={{ display: "flex", flexDirection: "column", gap: 10, alignSelf: "stretch" }} key={`${a.commissionPercent}-${a.phone}-${a.iban}-${a.note}`}>
          <h2>بيانات المسوّق</h2>
          <div className="grid2">
            <label className="f" htmlFor="fullName">الاسم<input id="fullName" name="fullName" defaultValue={a.fullName} required maxLength={100} /></label>
            <label className="f" htmlFor="rate">نسبة العمولة ٪<input id="rate" name="rate" type="number" min={0.01} max={100} step="0.01" defaultValue={a.commissionPercent} required /></label>
            <label className="f" htmlFor="phone">الجوال<input id="phone" name="phone" type="tel" dir="ltr" defaultValue={a.phone ?? ""} maxLength={30} /></label>
            <label className="f" htmlFor="iban">الآيبان<input id="iban" name="iban" dir="ltr" defaultValue={a.iban ?? ""} maxLength={40} /></label>
          </div>
          <label className="f" htmlFor="note">ملاحظة داخلية<input id="note" name="note" defaultValue={a.note ?? ""} maxLength={300} /></label>
          <div><button className="btn" disabled={busy === "save"}>{busy === "save" ? "جارٍ الحفظ…" : "احفظ"}</button></div>
        </form>
      </div>

      <PartnerView page={page} team />
    </>
  );
}
