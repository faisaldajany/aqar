"use client";

import { useEffect, useRef, useState } from "react";
import { api, ApiError, DAY_NAMES, type BookingSettings } from "@/lib/api";

const VARS = "{العميل} {العقار} {الحي} {اليوم} {الوقت} {من} {إلى} {الموقع} {طريقة_الدخول} {المسوق} {جوال_المسوق} {الرمز}";

export default function BookingSettingsPage() {
  const [s, setS] = useState<BookingSettings | null>(null);
  const [preview, setPreview] = useState({ rep: "", self: "" });
  const [msg, setMsg] = useState("");
  const [error, setError] = useState("");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    api<BookingSettings>("/booking-settings").then((x) => setS({ ...x, startTime: hhmm(x.startTime), endTime: hhmm(x.endTime), selfStartTime: hhmm(x.selfStartTime), selfEndTime: hhmm(x.selfEndTime) }))
      .catch(() => setError("تعذّر التحميل."));
  }, []);

  useEffect(() => {
    if (!s) return;
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      try {
        const [rep, self] = await Promise.all([
          api<{ text: string }>("/booking-settings/preview", { method: "POST", json: { template: s.templateRep, mode: "WITH_MARKETER" } }),
          api<{ text: string }>("/booking-settings/preview", { method: "POST", json: { template: s.templateSelf, mode: "SELF_VISIT" } }),
        ]);
        setPreview({ rep: rep.text, self: self.text });
      } catch {
        /* preview is best effort */
      }
    }, 400);
  }, [s]);

  if (!s) return <p className="muted">{error || "جارٍ التحميل…"}</p>;
  const set = <K extends keyof BookingSettings>(k: K, v: BookingSettings[K]) => setS({ ...s, [k]: v });

  async function save() {
    setMsg("");
    setError("");
    try {
      await api("/booking-settings", { method: "PUT", json: s });
      setMsg("حُفظت الإعدادات.");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر الحفظ.");
    }
  }

  return (
    <>
      <h1>إعداد المواعيد</h1>
      <p className="small muted">يحجز الوكيل ضمن هذه القواعد فقط، ويرسل للعميل التأكيد بصيغة رسائلك.</p>
      <div className="grid2">
        <div className="card" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          <h2>المعاينة مع مسوّق</h2>
          <div className="row">
            {DAY_NAMES.map((d, i) => (
              <label key={d} className="row small" style={{ gap: 4 }}>
                <input type="checkbox" checked={s.days.includes(i)} onChange={(e) => set("days", e.target.checked ? [...s.days, i].sort() : s.days.filter((x) => x !== i))} />{d}
              </label>
            ))}
          </div>
          <div className="grid2">
            <label className="f" htmlFor="st">من<input id="st" type="time" value={s.startTime} onChange={(e) => set("startTime", e.target.value)} /></label>
            <label className="f" htmlFor="et">إلى<input id="et" type="time" value={s.endTime} onChange={(e) => set("endTime", e.target.value)} /></label>
            <label className="f" htmlFor="slot">مدة الموعد (دقيقة)<input id="slot" type="number" min={15} step={15} value={s.slotMinutes} onChange={(e) => set("slotMinutes", Number(e.target.value))} /></label>
            <label className="f" htmlFor="buf">فاصل بين المواعيد<input id="buf" type="number" min={0} step={5} value={s.bufferMinutes} onChange={(e) => set("bufferMinutes", Number(e.target.value))} /></label>
            <label className="f" htmlFor="lead">أقرب موعد بعد (دقيقة)<input id="lead" type="number" min={0} step={15} value={s.minLeadMinutes} onChange={(e) => set("minLeadMinutes", Number(e.target.value))} /></label>
            <label className="f" htmlFor="rem">التذكير قبل (دقيقة)<input id="rem" type="number" min={15} step={15} value={s.reminderMinutes} onChange={(e) => set("reminderMinutes", Number(e.target.value))} /></label>
          </div>
          <label className="row small"><input type="checkbox" checked={s.followUpEnabled} onChange={(e) => set("followUpEnabled", e.target.checked)} />سؤال العميل عن انطباعه بعد الموعد بساعة</label>
        </div>
        <div className="card" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          <h2>الزيارة الذاتية</h2>
          <label className="row small"><input type="checkbox" checked={s.selfVisitEnabled} onChange={(e) => set("selfVisitEnabled", e.target.checked)} />السماح بالزيارة الذاتية</label>
          <label className="row small"><input type="checkbox" checked={s.selfVisitRequiresApproval} onChange={(e) => set("selfVisitRequiresApproval", e.target.checked)} />كل زيارة تنتظر موافقتي (ننصح بإبقائها)</label>
          <label className="row small"><input type="checkbox" checked={s.selfVacantOnly} onChange={(e) => set("selfVacantOnly", e.target.checked)} />للعقارات الشاغرة فقط</label>
          <label className="row small"><input type="checkbox" checked={s.selfQualifiedOnly} onChange={(e) => set("selfQualifiedOnly", e.target.checked)} />بعد معرفة ميزانية العميل وطريقة دفعه</label>
          <div className="grid2">
            <label className="f" htmlFor="sst">من<input id="sst" type="time" value={s.selfStartTime} onChange={(e) => set("selfStartTime", e.target.value)} /></label>
            <label className="f" htmlFor="set">إلى<input id="set" type="time" value={s.selfEndTime} onChange={(e) => set("selfEndTime", e.target.value)} /></label>
            <label className="f" htmlFor="cw">صلاحية رمز القفل قبل وبعد (دقيقة)<input id="cw" type="number" min={0} step={5} value={s.codeWindowMinutes} onChange={(e) => set("codeWindowMinutes", Number(e.target.value))} /></label>
          </div>
          <p className="small muted">طريقة الدخول لكل عقار تُحدد في صفحة تعديل العقار.</p>
        </div>
      </div>
      <div className="card" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <h2>صيغة رسالة التأكيد</h2>
        <p className="small muted">المتغيرات تُستبدل عند الإرسال: {VARS}</p>
        <div className="grid2">
          <label className="f" htmlFor="trep">معاينة مع مسوّق<textarea id="trep" rows={6} value={s.templateRep} onChange={(e) => set("templateRep", e.target.value)} /></label>
          <div><span className="small muted">ما يصل العميل</span><div className="card" style={{ whiteSpace: "pre-wrap", background: "var(--bubble-a)" }}>{preview.rep}</div></div>
          <label className="f" htmlFor="tself">زيارة ذاتية<textarea id="tself" rows={6} value={s.templateSelf} onChange={(e) => set("templateSelf", e.target.value)} /></label>
          <div><span className="small muted">ما يصل العميل</span><div className="card" style={{ whiteSpace: "pre-wrap", background: "var(--bubble-a)" }}>{preview.self}</div></div>
        </div>
      </div>
      <div className="row"><button className="btn primary" onClick={save}>حفظ</button>{msg && <span className="pill p-ok">{msg}</span>}{error && <span className="err">{error}</span>}</div>
    </>
  );
}

function hhmm(t: string) {
  return t.slice(0, 5);
}
