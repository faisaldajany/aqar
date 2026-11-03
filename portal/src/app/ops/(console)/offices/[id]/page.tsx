"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { api, ApiError, fmt, sar, SUB_LABELS } from "@/lib/api";
import { ACTION_LABELS, day, FAL_LABELS, PLAN_NAMES, when, type OpsDetail, type OpsOffice } from "@/lib/ops";
import { useOps } from "../../context";
import { TemplateStatus } from "@/components/TemplateStatus";

type Notice = { ok: boolean; text: string } | null;

export default function OpsOfficePage() {
  const { id } = useParams<{ id: string }>();
  const { mailConfigured } = useOps();
  const [o, setO] = useState<OpsDetail | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");
  const [notice, setNotice] = useState<Notice>(null);
  const [confirmSuspend, setConfirmSuspend] = useState(false);
  const [resetLinks, setResetLinks] = useState<Record<string, { url: string; expiresAt: string; emailed: boolean }>>({});

  const load = useCallback(async () => {
    try {
      setO(await api<OpsDetail>(`/ops/offices/${id}`));
    } catch (e) {
      setError(e instanceof ApiError && e.status === 404 ? "المكتب غير موجود." : "تعذّر التحميل.");
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  /** Runs one action, shows its result, and reloads the office. */
  async function act(name: string, path: string, body: unknown, done: string) {
    setBusy(name);
    setNotice(null);
    try {
      const res = await api<{ warning?: string | null }>(path, { method: "POST", json: body ?? {} });
      setNotice({ ok: true, text: res?.warning ? `${done} تنبيه: ${res.warning}` : done });
      await load();
      return true;
    } catch (e) {
      setNotice({ ok: false, text: e instanceof ApiError ? e.message : "تعذّر تنفيذ الإجراء." });
      return false;
    } finally {
      setBusy("");
    }
  }

  function form(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    return new FormData(e.currentTarget);
  }

  async function resetLink(userId: string, sendEmail: boolean) {
    setBusy(`reset-${userId}`);
    setNotice(null);
    try {
      const r = await api<{ url: string; expiresAt: string; emailed: boolean }>(`/ops/users/${userId}/reset-link`, { method: "POST", json: { sendEmail } });
      setResetLinks((x) => ({ ...x, [userId]: r }));
      await load();
    } catch (e) {
      setNotice({ ok: false, text: e instanceof ApiError ? e.message : "تعذّر إنشاء الرابط." });
    } finally {
      setBusy("");
    }
  }

  if (!o) return <p className="muted">{error || "جارٍ التحميل…"}</p>;
  const [subLabel, subCls] = SUB_LABELS[o.subscription];
  const [falLabel, falCls] = FAL_LABELS[o.falStatus];
  const neverPaid = !o.invoices.some((i) => i.kind === "SUBSCRIPTION" && i.status === "PAID");
  const canExtendTrial = neverPaid && (o.subscription === "TRIAL" || o.subscription === "EXPIRED");

  return (
    <>
      <p className="small"><Link href="/ops">← كل المكاتب</Link></p>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1 style={{ margin: 0 }}>{o.name}</h1>
        <div className="row">
          {o.paymentMode === "TEST" && <span className="pill p-acc" title="بريد مؤكد على نطاق حداء: يدفع ببطاقات الاختبار ولا يُحسب في الإيرادات">داخلي (دفع تجريبي)</span>}
          {o.paymentMode === "INTERNAL_UNCONFIRMED" && <span className="pill p-warn" title="بريد على نطاق حداء لم يُؤكد بعد: الدفع حقيقي">بريد حداء غير مؤكد</span>}
          {o.suspended && <span className="pill p-bad">موقوف</span>}
          <span className={`pill ${subCls}`}>{subLabel}</span>
          <span className="pill p-idle">{PLAN_NAMES[o.plan]}{o.cycle === "ANNUAL" ? " سنوي" : ""}</span>
          <span className={`pill ${falCls}`}>{falLabel}</span>
        </div>
      </div>
      {o.suspended && <p className="err">موقوف{o.suspendedReason ? `: ${o.suspendedReason}` : ""}. الوكيل لا يرد ولا يستطيع أحد من المكتب الدخول.</p>}
      {notice && <p className={notice.ok ? "card small" : "err"} role={notice.ok ? "status" : "alert"}>{notice.text}</p>}

      <div className="grid2">
        <div className="card" style={{ alignSelf: "stretch" }}>
          <h2>البيانات</h2>
          <dl className="facts">
            <dt>المالك</dt><dd dir="ltr">{o.ownerEmail ?? "—"}</dd>
            <dt>جوال المالك</dt><dd dir="ltr">{o.ownerPhone ?? "—"}</dd>
            <dt>رخصة فال</dt><dd dir="ltr">{o.falLicense}</dd>
            <dt>التجربة حتى</dt><dd>{day(o.trialEndsAt)}</dd>
            <dt>مدفوع حتى</dt><dd>{day(o.paidUntil)}</dd>
            <dt>واتساب</dt><dd>{o.whatsappConnected ? "مربوط" : "غير مربوط"}{waSetupLine(o.waSetup)}</dd>
            <dt>العقارات</dt><dd>{fmt(o.listings)}</dd>
            <dt>محادثات الشهر</dt><dd>{fmt(o.usage.conversations)} من {fmt(o.usage.included)}{o.usage.overage ? ` (+${o.usage.overage} إضافية)` : ""}</dd>
            <dt>كود الخصم</dt><dd>{o.promo ? `${o.promo.code}: ${o.promo.summary}${o.promo.usedUp ? " (انتهى)" : ` (استُخدم ${o.promo.periodsUsed})`}` : "—"}</dd>
            <dt>التسجيل</dt><dd>{when(o.createdAt)}</dd>
          </dl>
        </div>

        <div className="card" style={{ display: "flex", flexDirection: "column", gap: 10, alignSelf: "stretch" }}>
          <h2>رخصة فال</h2>
          {o.falNote && <p className="small muted">ملاحظة: {o.falNote}</p>}
          <p className="small muted">تحقق من الرقم في خدمة «الاستعلام عن رخص فال» لدى الهيئة العامة للعقار.</p>
          <form className="row" onSubmit={(e) => {
            const f = form(e);
            void act("fal", `/ops/offices/${o.id}/fal`, { status: f.get("status"), note: String(f.get("note") ?? "") || null }, "حُفظ قرار فال.");
          }}>
            <select name="status" aria-label="القرار" defaultValue="VERIFIED" style={{ flex: "1 1 120px" }}>
              <option value="VERIFIED">موثّقة</option>
              <option value="REJECTED">مرفوضة</option>
            </select>
            <input name="note" placeholder="ملاحظة للمكتب عند الرفض" aria-label="ملاحظة" style={{ flex: "2 1 200px" }} />
            <button className="btn" disabled={busy === "fal"}>حفظ</button>
          </form>
        </div>
      </div>

      <div className="grid3">
        <form className="card" style={{ display: "flex", flexDirection: "column", gap: 8, alignSelf: "stretch" }} onSubmit={(e) => {
          const f = form(e);
          void act("trial", `/ops/offices/${o.id}/trial`, { days: Number(f.get("days")) }, "مُدّدت التجربة.");
        }}>
          <h2>تمديد التجربة</h2>
          {canExtendTrial ? <>
            <label className="f" htmlFor="days">أيام إضافية<input id="days" name="days" type="number" min={1} max={180} defaultValue={7} required /></label>
            <button className="btn" disabled={busy === "trial"}>مدّد</button>
          </> : <p className="small muted">للمكاتب التي لم تشترك بعد. للمشتركين استخدم الأشهر المجانية.</p>}
        </form>

        <form className="card" style={{ display: "flex", flexDirection: "column", gap: 8, alignSelf: "stretch" }} onSubmit={(e) => {
          const f = form(e);
          void act("comp", `/ops/offices/${o.id}/comp`, { months: Number(f.get("months")), plan: f.get("plan"), reason: String(f.get("reason") ?? "") || null }, "أُضيفت الأشهر المجانية.");
        }}>
          <h2>أشهر مجانية</h2>
          <div className="row">
            <input name="months" type="number" min={1} max={24} defaultValue={1} required aria-label="عدد الأشهر" style={{ flex: "1 1 70px" }} />
            <select name="plan" defaultValue={o.plan} aria-label="الباقة" style={{ flex: "2 1 120px" }}>
              {(Object.keys(PLAN_NAMES) as OpsOffice["plan"][]).map((p) => <option key={p} value={p}>{PLAN_NAMES[p]}</option>)}
            </select>
          </div>
          <input name="reason" placeholder="السبب (يظهر في الفاتورة)" aria-label="السبب" />
          <button className="btn" disabled={busy === "comp"}>أضف</button>
        </form>

        <form className="card" style={{ display: "flex", flexDirection: "column", gap: 8, alignSelf: "stretch" }} onSubmit={(e) => {
          const f = form(e);
          void act("plan", `/ops/offices/${o.id}/plan`, { plan: f.get("plan") }, "تغيّرت الباقة. تُطبّق من الفاتورة القادمة.");
        }}>
          <h2>تغيير الباقة</h2>
          <select name="plan" defaultValue={o.plan} aria-label="الباقة">
            {(Object.keys(PLAN_NAMES) as OpsOffice["plan"][]).map((p) => <option key={p} value={p}>{PLAN_NAMES[p]}</option>)}
          </select>
          <p className="small muted">دون دفع. حدود الباقة الجديدة تسري فوراً.</p>
          <button className="btn" disabled={busy === "plan"}>غيّر</button>
        </form>
      </div>

      <div className="card" style={{ display: "flex", flexDirection: "column", gap: 8, alignSelf: "stretch" }}>
        <h2>إيقاف المكتب</h2>
        {o.suspended ? (
          <div className="row">
            <button type="button" className="btn primary" disabled={busy === "reactivate"}
              onClick={() => void act("reactivate", `/ops/offices/${o.id}/reactivate`, {}, "أُعيد تفعيل المكتب.")}>إعادة التفعيل</button>
          </div>
        ) : (
          <form className="row" onSubmit={async (e) => {
            const f = form(e);
            if (!confirmSuspend) { setConfirmSuspend(true); return; }
            if (await act("suspend", `/ops/offices/${o.id}/suspend`, { reason: String(f.get("reason") ?? "") || null }, "أُوقف المكتب.")) setConfirmSuspend(false);
          }}>
            <input name="reason" placeholder="السبب (للسجل)" aria-label="سبب الإيقاف" style={{ flex: "2 1 220px" }} />
            <button className={`btn${confirmSuspend ? " reply" : ""}`} disabled={busy === "suspend"}>
              {confirmSuspend ? "اضغط مرة ثانية للتأكيد" : "أوقف المكتب"}
            </button>
            {confirmSuspend && <button type="button" className="btn" onClick={() => setConfirmSuspend(false)}>تراجع</button>}
          </form>
        )}
        <p className="small muted">يتوقف الوكيل عن الرد، ويُسجَّل خروج كل مستخدمي المكتب ولا يستطيعون الدخول حتى إعادة التفعيل. البيانات تبقى كما هي.</p>
      </div>

      {o.whatsappConnected && (
        <TemplateStatus rows={o.templates ?? []} busy={busy === "templates"}
          onSync={() => void act("templates", `/ops/offices/${o.id}/templates/sync`, {}, "حُدّثت حالة القوالب.")} />
      )}

      <div className="card">
        <h2>المستخدمون</h2>
        <div className="tbl"><table>
          <thead><tr><th>الاسم</th><th>البريد</th><th>الدور</th><th>آخر دخول</th><th>الحالة</th><th /></tr></thead>
          <tbody>
            {o.users.map((u) => (
              <tr key={u.id}>
                <td>{u.fullName}</td>
                <td dir="ltr">{u.email}</td>
                <td>{u.role === "OWNER" ? "مالك" : u.role === "MANAGER" ? "مدير" : "مسوّق"}</td>
                <td className="small">{when(u.lastLoginAt)}</td>
                <td>{u.disabled ? <span className="pill p-bad">موقوف</span> : <span className="pill p-ok">فعّال</span>}</td>
                <td>
                  <div className="row">
                    <button type="button" className="btn" disabled={busy === `user-${u.id}`}
                      onClick={() => void act(`user-${u.id}`, `/ops/users/${u.id}/${u.disabled ? "enable" : "disable"}`, {}, u.disabled ? "فُعّل المستخدم." : "أُوقف المستخدم وانتهت جلساته.")}>
                      {u.disabled ? "تفعيل" : "إيقاف"}
                    </button>
                    <button type="button" className="btn" disabled={busy === `reset-${u.id}`} onClick={() => void resetLink(u.id, false)}>رابط كلمة مرور</button>
                    {mailConfigured && <button type="button" className="btn" disabled={busy === `reset-${u.id}`} onClick={() => void resetLink(u.id, true)}>أرسله بالبريد</button>}
                  </div>
                  {resetLinks[u.id] && (
                    <div className="small" style={{ marginTop: 6 }}>
                      {resetLinks[u.id].emailed ? "أُرسل بالبريد. " : ""}صالح حتى {when(resetLinks[u.id].expiresAt)}:
                      <div className="row">
                        <input readOnly value={resetLinks[u.id].url} dir="ltr" aria-label="الرابط" onFocus={(e) => e.currentTarget.select()} style={{ flex: "1 1 240px" }} />
                        <button type="button" className="btn" onClick={() => void navigator.clipboard?.writeText(resetLinks[u.id].url)}>نسخ</button>
                      </div>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table></div>
      </div>

      <div className="card">
        <h2>الفواتير</h2>
        {o.invoices.length === 0 ? <p className="small muted">لا فواتير.</p> : (
          <div className="tbl"><table>
            <thead><tr><th>الرقم</th><th>الوصف</th><th>الخصم</th><th>الإجمالي</th><th>الحالة</th><th>التاريخ</th></tr></thead>
            <tbody>
              {o.invoices.map((i) => (
                <tr key={i.id}>
                  <td className="mono small">{i.number}</td>
                  <td className="small">{i.description}</td>
                  <td>{i.discountHalalas ? `${sar(i.discountHalalas)} ريال` : "—"}</td>
                  <td>{sar(i.totalHalalas)} ريال</td>
                  <td>{i.status === "PAID" ? <span className="pill p-ok">مدفوعة</span> : i.status === "DUE" ? <span className="pill p-warn">مستحقة</span> : <span className="pill p-idle">ملغاة</span>}</td>
                  <td className="small">{day(i.paidAt ?? i.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table></div>
        )}
      </div>

      <div className="card">
        <h2>سجل العمليات على المكتب</h2>
        {o.audit.length === 0 ? <p className="small muted">لا عمليات بعد.</p> : (
          <ul className="small" style={{ margin: 0, paddingInlineStart: 18 }}>
            {o.audit.map((a, n) => <li key={n}>{when(a.at)}: {ACTION_LABELS[a.action] ?? a.action}{a.detail ? ` (${a.detail})` : ""}</li>)}
          </ul>
        )}
      </div>
    </>
  );
}

/** "· خطوات الإعداد 3 من 8، متوقف عند: الملف التجاري (الربط من الأحد 9 نوفمبر)". */
function waSetupLine(w: OpsDetail["waSetup"]): string {
  if (!w || !w.current) return "";
  const step = w.steps.find((s) => s.key === w.current)?.label ?? w.current;
  const ready = w.current === "active" && w.readyOn ? ` (الربط من ${day(w.readyOn)})` : "";
  return ` · الإعداد ${w.done} من ${w.total}، الخطوة الحالية: ${step}${ready}${w.phone ? ` · ${w.phone}` : ""}`;
}
