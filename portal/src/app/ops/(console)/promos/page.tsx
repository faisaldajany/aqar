"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { day, PLAN_NAMES, type OpsOffice, type OpsPromo } from "@/lib/ops";

type Kind = OpsPromo["kind"];
type Marketer = { id: string; fullName: string; commissionPercent: number };

export default function OpsPromos() {
  const [promos, setPromos] = useState<OpsPromo[] | null>(null);
  const [kind, setKind] = useState<Kind>("PERCENT");
  const [busy, setBusy] = useState("");
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);
  const [origin, setOrigin] = useState("https://hidaaco.com");
  const [marketers, setMarketers] = useState<Marketer[]>([]);
  const [marketer, setMarketer] = useState("");

  const load = useCallback(async () => setPromos(await api<OpsPromo[]>("/ops/promos")), []);
  useEffect(() => {
    setOrigin(window.location.origin);
    // "Create a code for this marketer" links here with ?affiliate=<id>.
    setMarketer(new URLSearchParams(window.location.search).get("affiliate") ?? "");
    api<Marketer[]>("/ops/affiliates/options").then(setMarketers).catch(() => undefined);
    load().catch(() => setNotice({ ok: false, text: "تعذّر التحميل." }));
  }, [load]);
  const marketerName = (id: string | null) => marketers.find((m) => m.id === id)?.fullName;

  async function create(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const formEl = e.currentTarget;
    const f = new FormData(formEl);
    const num = (k: string) => (String(f.get(k) ?? "").trim() === "" ? null : Number(f.get(k)));
    const text = (k: string) => String(f.get(k) ?? "").trim() || null;
    setBusy("create");
    setNotice(null);
    try {
      const p = await api<OpsPromo>("/ops/promos", {
        method: "POST",
        json: {
          code: text("code"), kind,
          percentOff: kind === "PERCENT" ? num("value") : null,
          amountOffSar: kind === "FIXED" ? num("value") : null,
          trialDays: kind === "TRIAL_DAYS" ? num("value") : null,
          durationPeriods: kind === "TRIAL_DAYS" ? null : num("duration"),
          plans: f.getAll("plans").map(String),
          cycles: f.getAll("cycles").map(String),
          newCustomersOnly: f.get("newOnly") === "on",
          maxRedemptions: num("max"),
          startsOn: text("startsOn"), expiresOn: text("expiresOn"), note: text("note"),
          affiliateId: marketer || null,
        },
      });
      setNotice({ ok: true, text: `أُنشئ ${p.code}: ${p.summary}` });
      formEl.reset();
      await load();
    } catch (err) {
      setNotice({ ok: false, text: err instanceof ApiError ? err.message : "تعذّر إنشاء الكود." });
    } finally {
      setBusy("");
    }
  }

  async function toggle(p: OpsPromo) {
    setBusy(p.id);
    try {
      await api(`/ops/promos/${p.id}/active`, { method: "POST", json: { active: !p.active } });
      await load();
    } finally {
      setBusy("");
    }
  }

  return (
    <>
      <h1>أكواد الخصم</h1>
      <details className="card">
        <summary><b>كيف تعمل الأكواد</b></summary>
        <ul className="small" style={{ marginBottom: 0 }}>
          <li><b>نسبة أو مبلغ:</b> خصم على فواتير الاشتراك فقط (لا المحادثات الإضافية)، لعدد الدفعات الذي تحدده. الدفعة شهر في الشهري وسنة في السنوي. فارغ = كل التجديدات.</li>
          <li>الضريبة تُحسب على المبلغ بعد الخصم. خصم 100٪ يفعّل الفترة دون صفحة دفع ويتجدد تلقائياً حتى تنتهي دفعاته.</li>
          <li><b>أيام تجربة:</b> تُضاف للتجربة المجانية فوراً، للحسابات التي لم تشترك بعد.</li>
          <li>كل مكتب يستخدم الكود مرة واحدة، وخصماً واحداً في الوقت نفسه. يُحسب الاستخدام عند تطبيق الكود، فلا يتجاوز الحد الأقصى أبداً، ويعود إذا أزال المكتب الكود قبل أي دفعة.</li>
          <li>الخصم الذي بدأ يستمر لمدته حتى لو انتهى الكود أو أوقفته. الإيقاف يمنع الاستخدام الجديد فقط.</li>
          <li>رابط التسجيل بالكود: <span dir="ltr">{origin}/signup?promo=CODE</span></li>
        </ul>
      </details>

      <form className="card" onSubmit={create} style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <h2>كود جديد</h2>
        <div className="grid3">
          <label className="f" htmlFor="code">الكود<input id="code" name="code" dir="ltr" required minLength={3} maxLength={32} pattern="[A-Za-z0-9_\-]{3,32}" placeholder="LAUNCH30" autoCapitalize="characters" /></label>
          <label className="f" htmlFor="kind">النوع
            <select id="kind" value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
              <option value="PERCENT">نسبة خصم ٪</option>
              <option value="FIXED">مبلغ خصم (ريال)</option>
              <option value="TRIAL_DAYS">أيام تجربة إضافية</option>
            </select>
          </label>
          <label className="f" htmlFor="value">{kind === "PERCENT" ? "النسبة ٪" : kind === "FIXED" ? "المبلغ بالريال (قبل الضريبة)" : "عدد الأيام"}
            <input id="value" name="value" type="number" required min={1} max={kind === "PERCENT" ? 100 : kind === "TRIAL_DAYS" ? 180 : undefined} step={kind === "FIXED" ? "0.01" : "1"} />
          </label>
        </div>
        {kind !== "TRIAL_DAYS" && (
          <div className="grid3">
            <label className="f" htmlFor="duration">عدد الدفعات المخصومة<input id="duration" name="duration" type="number" min={1} max={36} placeholder="فارغ = كل الدفعات" /></label>
            <fieldset className="f" style={{ border: 0, padding: 0, margin: 0 }}>
              <legend className="small" style={{ fontWeight: 600 }}>الباقات (لا شيء = الكل)</legend>
              <div className="row">{(Object.keys(PLAN_NAMES) as OpsOffice["plan"][]).map((p) => (
                <label key={p} className="check"><input type="checkbox" name="plans" value={p} />{PLAN_NAMES[p]}</label>
              ))}</div>
            </fieldset>
            <fieldset className="f" style={{ border: 0, padding: 0, margin: 0 }}>
              <legend className="small" style={{ fontWeight: 600 }}>طريقة الدفع (لا شيء = الكل)</legend>
              <div className="row">
                <label className="check"><input type="checkbox" name="cycles" value="MONTHLY" />شهري</label>
                <label className="check"><input type="checkbox" name="cycles" value="ANNUAL" />سنوي</label>
              </div>
            </fieldset>
          </div>
        )}
        <div className="grid3">
          <label className="f" htmlFor="max">أقصى عدد مكاتب<input id="max" name="max" type="number" min={1} placeholder="فارغ = بلا حد" /></label>
          <label className="f" htmlFor="startsOn">يبدأ في<input id="startsOn" name="startsOn" type="date" /></label>
          <label className="f" htmlFor="expiresOn">ينتهي في (شاملاً)<input id="expiresOn" name="expiresOn" type="date" /></label>
        </div>
        <label className="check"><input type="checkbox" name="newOnly" defaultChecked />للمشتركين الجدد فقط (لم يدفعوا اشتراكاً من قبل)</label>
        <label className="f" htmlFor="marketer">المسوّق صاحب الكود
          <select id="marketer" value={marketer} onChange={(e) => setMarketer(e.target.value)}>
            <option value="">بلا مسوّق</option>
            {marketers.map((m) => <option key={m.id} value={m.id}>{m.fullName} (عمولة {m.commissionPercent}٪)</option>)}
          </select>
          {marketer && <span className="small muted">المكتب الذي يطبّق الكود يصبح عميلاً لهذا المسوّق، ويكسب عمولته من كل دفعة ما دام الكود فعّالاً. يُفضّل ترك «عدد الدفعات المخصومة» فارغاً.</span>}
        </label>
        <label className="f" htmlFor="note">ملاحظة داخلية<input id="note" name="note" maxLength={200} placeholder="مثل: معرض سيتي سكيب" /></label>
        {notice && <p className={notice.ok ? "small" : "err"} role={notice.ok ? "status" : "alert"}>{notice.text}</p>}
        <div><button className="btn primary" disabled={busy === "create"}>{busy === "create" ? "جارٍ الإنشاء…" : "أنشئ الكود"}</button></div>
      </form>

      <div className="tbl"><table>
        <thead><tr><th>الكود</th><th>الخصم</th><th>الاستخدام</th><th>الصلاحية</th><th>الحالة</th><th /></tr></thead>
        <tbody>
          {promos == null && <tr><td colSpan={6} className="muted">جارٍ التحميل…</td></tr>}
          {promos?.length === 0 && <tr><td colSpan={6} className="muted">لا أكواد بعد.</td></tr>}
          {promos?.map((p) => (
            <tr key={p.id}>
              <td><b dir="ltr">{p.code}</b>{p.note && <><br /><span className="small muted">{p.note}</span></>}
                {p.affiliateId && <><br /><Link className="small" href={`/ops/affiliates/${p.affiliateId}`}>المسوّق: {marketerName(p.affiliateId) ?? "—"}</Link></>}</td>
              <td className="small">{p.summary}{p.newCustomersOnly ? " · للجدد" : ""}</td>
              <td>{p.redemptions}{p.maxRedemptions ? ` / ${p.maxRedemptions}` : ""}</td>
              <td className="small">{p.startsAt ? `من ${day(p.startsAt)} ` : ""}{p.expiresAt ? `حتى ${day(p.expiresAt)}` : "بلا انتهاء"}</td>
              <td>{p.active ? <span className="pill p-ok">فعّال</span> : <span className="pill p-idle">موقوف</span>}</td>
              <td>
                <div className="row">
                  <button type="button" className="btn" disabled={busy === p.id} onClick={() => void toggle(p)}>{p.active ? "إيقاف" : "تفعيل"}</button>
                  <button type="button" className="btn" onClick={() => void navigator.clipboard?.writeText(`${origin}/signup?promo=${p.code}`)}>نسخ رابط التسجيل</button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table></div>
    </>
  );
}
