"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
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
  /** The code being edited; null = creating a new one. */
  const [editing, setEditing] = useState<OpsPromo | null>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const fromLink = useRef<string | null>(null);

  const load = useCallback(async () => {
    const list = await api<OpsPromo[]>("/ops/promos");
    setPromos(list);
    return list;
  }, []);

  const startEdit = useCallback((p: OpsPromo) => {
    setEditing(p);
    setKind(p.kind);
    setMarketer(p.affiliateId ?? "");
    setNotice(null);
    window.setTimeout(() => formRef.current?.scrollIntoView({ behavior: "smooth", block: "start" }), 0);
  }, []);

  function stopEdit() {
    setEditing(null);
    setKind("PERCENT");
    setMarketer("");
    setNotice(null);
  }

  useEffect(() => {
    setOrigin(window.location.origin);
    // "A code for this marketer" links here with ?affiliate=<id>: their code opens for editing if they have one.
    fromLink.current = new URLSearchParams(window.location.search).get("affiliate");
    setMarketer(fromLink.current ?? "");
    api<Marketer[]>("/ops/affiliates/options").then(setMarketers).catch(() => undefined);
    load().then((list) => {
      const own = fromLink.current && list.find((p) => p.affiliateId === fromLink.current && p.active);
      if (own) {
        startEdit(own);
        setNotice({ ok: true, text: `لهذا المسوّق كود فعّال (${own.code})، فهو مفتوح للتعديل.` });
      }
    }).catch(() => setNotice({ ok: false, text: "تعذّر التحميل." }));
  }, [load, startEdit]);

  const marketerName = (id: string | null) => marketers.find((m) => m.id === id)?.fullName;
  /** Used by at least one office: its terms stay as they are. */
  const locked = editing != null && editing.redemptions > 0;
  /** The chosen marketer's other active code: a marketer has one at a time. */
  const taken = marketer ? promos?.find((p) => p.affiliateId === marketer && p.active && p.id !== editing?.id) : undefined;
  const blocked = taken != null && (editing == null || editing.active);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const formEl = e.currentTarget;
    const f = new FormData(formEl);
    const num = (k: string) => (String(f.get(k) ?? "").trim() === "" ? null : Number(f.get(k)));
    const text = (k: string) => String(f.get(k) ?? "").trim() || null;
    // Locked fields are disabled (not in the form data): they go back as they are.
    const terms = locked && editing ? {
      code: editing.code, kind: editing.kind, percentOff: editing.percentOff,
      amountOffSar: editing.amountOffHalalas == null ? null : editing.amountOffHalalas / 100,
      trialDays: editing.trialDays, durationPeriods: editing.durationPeriods,
    } : {
      code: text("code"), kind,
      percentOff: kind === "PERCENT" ? num("value") : null,
      amountOffSar: kind === "FIXED" ? num("value") : null,
      trialDays: kind === "TRIAL_DAYS" ? num("value") : null,
      durationPeriods: kind === "TRIAL_DAYS" ? null : num("duration"),
    };
    setBusy("save");
    setNotice(null);
    try {
      const p = await api<OpsPromo>(editing ? `/ops/promos/${editing.id}` : "/ops/promos", {
        method: editing ? "PUT" : "POST",
        json: {
          ...terms,
          plans: f.getAll("plans").map(String),
          cycles: f.getAll("cycles").map(String),
          newCustomersOnly: f.get("newOnly") === "on",
          maxRedemptions: num("max"),
          startsOn: text("startsOn"), expiresOn: text("expiresOn"), note: text("note"),
          affiliateId: marketer || null,
        },
      });
      if (editing) stopEdit();
      else formEl.reset();
      setNotice({ ok: true, text: `${editing ? "حُفظ" : "أُنشئ"} ${p.code}: ${p.summary}` });
      await load();
    } catch (err) {
      setNotice({ ok: false, text: err instanceof ApiError ? err.message : "تعذّر الحفظ." });
    } finally {
      setBusy("");
    }
  }

  async function toggle(p: OpsPromo) {
    setBusy(p.id);
    setNotice(null);
    try {
      await api(`/ops/promos/${p.id}/active`, { method: "POST", json: { active: !p.active } });
      await load();
    } catch (err) {
      setNotice({ ok: false, text: err instanceof ApiError ? err.message : "تعذّر التنفيذ." });
    } finally {
      setBusy("");
    }
  }

  const value = editing == null ? undefined
    : editing.kind === "PERCENT" ? editing.percentOff ?? undefined
    : editing.kind === "FIXED" ? (editing.amountOffHalalas ?? 0) / 100 : editing.trialDays ?? undefined;
  const marketerMissing = editing?.affiliateId && !marketers.some((m) => m.id === editing.affiliateId);

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
          <li><b>التعديل:</b> قبل أن يستخدمه أي مكتب يتغير كل شيء. بعد الاستخدام يبقى الكود والخصم وعدد دفعاته كما هي حتى لا يتغير السعر على من يستخدمه، ويتغير الباقي (التواريخ، الحد، الباقات، الملاحظة، المسوّق).</li>
          <li><b>المسوّق:</b> له كود فعّال واحد. لعرض جديد عدّل كوده، أو أوقفه ثم أنشئ غيره.</li>
          <li>رابط التسجيل بالكود: <span dir="ltr">{origin}/signup?promo=CODE</span></li>
        </ul>
      </details>

      <form ref={formRef} key={editing?.id ?? "new"} className="card" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 10, scrollMarginTop: 16 }}>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2>{editing ? <>تعديل الكود <bdi dir="ltr">{editing.code}</bdi></> : "كود جديد"}</h2>
          {editing && <button type="button" className="btn" onClick={stopEdit}>إلغاء التعديل</button>}
        </div>
        {locked && (
          <p className="small muted" style={{ margin: 0 }}>
            استخدمه {editing!.redemptions === 1 ? "مكتب واحد" : `${editing!.redemptions} مكاتب`}، فالكود والخصم وعدد دفعاته ثابتة حتى لا يتغير السعر عليهم.
            لعرض جديد أنشئ كوداً آخر وأوقف هذا.
          </p>
        )}
        <div className="grid3">
          <label className="f" htmlFor="code">الكود<input id="code" name="code" dir="ltr" required minLength={3} maxLength={32} pattern="[A-Za-z0-9_\-]{3,32}"
            placeholder="LAUNCH30" autoCapitalize="characters" defaultValue={editing?.code} disabled={locked} /></label>
          <label className="f" htmlFor="kind">النوع
            <select id="kind" value={kind} onChange={(e) => setKind(e.target.value as Kind)} disabled={locked}>
              <option value="PERCENT">نسبة خصم ٪</option>
              <option value="FIXED">مبلغ خصم (ريال)</option>
              <option value="TRIAL_DAYS">أيام تجربة إضافية</option>
            </select>
          </label>
          <label className="f" htmlFor="value">{kind === "PERCENT" ? "النسبة ٪" : kind === "FIXED" ? "المبلغ بالريال (قبل الضريبة)" : "عدد الأيام"}
            <input id="value" name="value" type="number" required min={1} max={kind === "PERCENT" ? 100 : kind === "TRIAL_DAYS" ? 180 : undefined}
              step={kind === "FIXED" ? "0.01" : "1"} defaultValue={editing?.kind === kind ? value : undefined} disabled={locked} />
          </label>
        </div>
        {kind !== "TRIAL_DAYS" && (
          <div className="grid3">
            <label className="f" htmlFor="duration">عدد الدفعات المخصومة<input id="duration" name="duration" type="number" min={1} max={36}
              placeholder="فارغ = كل الدفعات" defaultValue={editing?.durationPeriods ?? undefined} disabled={locked} /></label>
            <fieldset className="f" style={{ border: 0, padding: 0, margin: 0 }}>
              <legend className="small" style={{ fontWeight: 600 }}>الباقات (لا شيء = الكل)</legend>
              <div className="row">{(Object.keys(PLAN_NAMES) as OpsOffice["plan"][]).map((p) => (
                <label key={p} className="check"><input type="checkbox" name="plans" value={p} defaultChecked={editing?.plans.includes(p)} />{PLAN_NAMES[p]}</label>
              ))}</div>
            </fieldset>
            <fieldset className="f" style={{ border: 0, padding: 0, margin: 0 }}>
              <legend className="small" style={{ fontWeight: 600 }}>طريقة الدفع (لا شيء = الكل)</legend>
              <div className="row">
                <label className="check"><input type="checkbox" name="cycles" value="MONTHLY" defaultChecked={editing?.cycles.includes("MONTHLY")} />شهري</label>
                <label className="check"><input type="checkbox" name="cycles" value="ANNUAL" defaultChecked={editing?.cycles.includes("ANNUAL")} />سنوي</label>
              </div>
            </fieldset>
          </div>
        )}
        <div className="grid3">
          <label className="f" htmlFor="max">أقصى عدد مكاتب<input id="max" name="max" type="number" min={Math.max(1, editing?.redemptions ?? 1)}
            placeholder="فارغ = بلا حد" defaultValue={editing?.maxRedemptions ?? undefined} /></label>
          <label className="f" htmlFor="startsOn">يبدأ في<input id="startsOn" name="startsOn" type="date" defaultValue={editing?.startsOn ?? undefined} /></label>
          <label className="f" htmlFor="expiresOn">ينتهي في (شاملاً)<input id="expiresOn" name="expiresOn" type="date" defaultValue={editing?.expiresOn ?? undefined} /></label>
        </div>
        <label className="check"><input type="checkbox" name="newOnly" defaultChecked={editing ? editing.newCustomersOnly : true} />للمشتركين الجدد فقط (لم يدفعوا اشتراكاً من قبل)</label>
        <label className="f" htmlFor="marketer">المسوّق صاحب الكود
          <select id="marketer" value={marketer} onChange={(e) => setMarketer(e.target.value)}>
            <option value="">بلا مسوّق</option>
            {marketerMissing && <option value={editing!.affiliateId!}>المسوّق الحالي (موقوف)</option>}
            {marketers.map((m) => <option key={m.id} value={m.id}>{m.fullName} (عمولة {m.commissionPercent}٪)</option>)}
          </select>
          {taken ? (
            <span className="err" role="alert" style={{ margin: 0 }}>
              لهذا المسوّق كود فعّال: <bdi dir="ltr">{taken.code}</bdi>. له كود واحد في الوقت نفسه.{" "}
              <button type="button" className="btn" onClick={() => startEdit(taken)}>عدّل كوده</button>
            </span>
          ) : marketer ? (
            <span className="small muted">
              المكتب الذي يطبّق الكود يصبح عميلاً لهذا المسوّق، ويكسب عمولته من كل دفعة ما دام الكود فعّالاً. يُفضّل ترك «عدد الدفعات المخصومة» فارغاً.
              {editing && editing.affiliateId !== (marketer || null) && " عملاء الكود الحاليون ينتقلون للمسوّق الجديد من الآن، وعمولاتهم السابقة تبقى للسابق."}
            </span>
          ) : null}
        </label>
        <label className="f" htmlFor="note">ملاحظة داخلية<input id="note" name="note" maxLength={200} placeholder="مثل: معرض سيتي سكيب" defaultValue={editing?.note ?? undefined} /></label>
        {notice && <p className={notice.ok ? "small" : "err"} role={notice.ok ? "status" : "alert"}>{notice.text}</p>}
        <div><button className="btn primary" disabled={busy === "save" || blocked}>
          {busy === "save" ? "جارٍ الحفظ…" : editing ? "احفظ التعديل" : "أنشئ الكود"}
        </button></div>
      </form>

      <div className="tbl"><table>
        <thead><tr><th>الكود</th><th>الخصم</th><th>الاستخدام</th><th>الصلاحية</th><th>الحالة</th><th /></tr></thead>
        <tbody>
          {promos == null && <tr><td colSpan={6} className="muted">جارٍ التحميل…</td></tr>}
          {promos?.length === 0 && <tr><td colSpan={6} className="muted">لا أكواد بعد.</td></tr>}
          {promos?.map((p) => (
            <tr key={p.id} aria-current={editing?.id === p.id ? "true" : undefined}>
              <td><b dir="ltr">{p.code}</b>{p.note && <><br /><span className="small muted">{p.note}</span></>}
                {p.affiliateId && <><br /><Link className="small" href={`/ops/affiliates/${p.affiliateId}`}>المسوّق: {marketerName(p.affiliateId) ?? "—"}</Link></>}</td>
              <td className="small">{p.summary}{p.newCustomersOnly ? " · للجدد" : ""}</td>
              <td>{p.redemptions}{p.maxRedemptions ? ` / ${p.maxRedemptions}` : ""}</td>
              <td className="small">{p.startsOn ? `من ${day(p.startsOn)} ` : ""}{p.expiresOn ? `حتى ${day(p.expiresOn)}` : "بلا انتهاء"}</td>
              <td>{p.active ? <span className="pill p-ok">فعّال</span> : <span className="pill p-idle">موقوف</span>}</td>
              <td>
                <div className="row">
                  <button type="button" className="btn" onClick={() => startEdit(p)}>تعديل</button>
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
