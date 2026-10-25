"use client";

import { FormEvent, useEffect, useState } from "react";
import { api, ApiError, type DailyReportRow, type OfficeSettings } from "@/lib/api";

const FAL: Record<OfficeSettings["falStatus"], [string, string]> = {
  PENDING_REVIEW: ["قيد التحقق", "p-warn"], VERIFIED: ["موثّقة", "p-ok"], REJECTED: ["لم نتمكن من التحقق", "p-bad"],
};

export default function SettingsPage() {
  const [s, setS] = useState<OfficeSettings | null>(null);
  const [reports, setReports] = useState<DailyReportRow[]>([]);
  const [today, setToday] = useState("");
  const [msg, setMsg] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    api<OfficeSettings>("/office").then(setS).catch(() => setError("تعذّر التحميل."));
    api<DailyReportRow[]>("/reports").then(setReports).catch(() => undefined);
    api<{ body: string }>("/reports/today").then((r) => setToday(r.body)).catch(() => undefined);
  }, []);

  if (!s) return <p className="muted">{error || "جارٍ التحميل…"}</p>;
  const set = <K extends keyof OfficeSettings>(k: K, v: OfficeSettings[K]) => setS({ ...s, [k]: v });

  async function save(e: FormEvent) {
    e.preventDefault();
    setMsg("");
    setError("");
    try {
      setS(await api<OfficeSettings>("/office", { method: "PUT", json: s }));
      setMsg("حُفظت الإعدادات.");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر الحفظ.");
    }
  }

  async function sendNow() {
    setMsg("");
    await api("/reports/send-now", { method: "POST" }).catch(() => undefined);
    setReports(await api<DailyReportRow[]>("/reports"));
  }

  return (
    <>
      <h1>الإعدادات</h1>
      <form className="card" onSubmit={save} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <h2>المكتب</h2>
        <div className="grid2">
          <label className="f" htmlFor="name">اسم المكتب كما يظهر للعملاء<input id="name" type="text" required value={s.name} onChange={(e) => set("name", e.target.value)} /></label>
          <label className="f" htmlFor="fal">رقم رخصة فال <span className={`pill ${FAL[s.falStatus][1]}`}>{FAL[s.falStatus][0]}</span>
            <input id="fal" type="text" inputMode="numeric" className="mono" required value={s.falLicense} onChange={(e) => set("falLicense", e.target.value)} /></label>
        </div>
        {s.falStatus === "REJECTED" && s.falNote && <p className="err small">{s.falNote}</p>}
        <h2>التقرير اليومي للمدير</h2>
        <p className="small muted">ملخص اليوم يصل جوالك على واتساب من رقم المكتب: الاستفسارات، العملاء الجادون، المواعيد، التحويلات.</p>
        <div className="grid3">
          <label className="f" htmlFor="phone">جوال المدير<input id="phone" type="tel" dir="ltr" placeholder="05xxxxxxxx" value={s.ownerPhone ?? ""} onChange={(e) => set("ownerPhone", e.target.value)} /></label>
          <label className="f" htmlFor="hour">وقت الإرسال
            <select id="hour" value={s.reportHour} onChange={(e) => set("reportHour", Number(e.target.value))}>
              {Array.from({ length: 24 }, (_, h) => <option key={h} value={h}>{`${String(h).padStart(2, "0")}:00`}</option>)}
            </select></label>
          <label className="row small" style={{ alignSelf: "end" }}><input type="checkbox" checked={s.reportEnabled} onChange={(e) => set("reportEnabled", e.target.checked)} />أرسل التقرير يومياً</label>
        </div>
        <div className="row">
          <button className="btn primary">حفظ</button>
          {msg && <span className="pill p-ok">{msg}</span>}
          {error && <span className="err">{error}</span>}
        </div>
      </form>
      <div className="grid2">
        <div className="card">
          <div className="row" style={{ justifyContent: "space-between" }}><h2>تقرير اليوم حتى الآن</h2><button className="btn" onClick={sendNow}>أرسله الآن</button></div>
          <div style={{ whiteSpace: "pre-wrap" }} className="small">{today}</div>
        </div>
        <div className="card">
          <h2>التقارير السابقة</h2>
          {reports.length === 0 ? <p className="small muted">لا تقارير بعد.</p> : reports.map((r) => (
            <details key={r.date} style={{ borderBottom: "1px solid var(--line)", padding: "6px 0" }}>
              <summary className="small">{r.date} · {r.sentTo ? <span className="pill p-ok">وصل جوالك</span> : <span className="pill p-idle">في البوابة فقط</span>}</summary>
              <div className="small" style={{ whiteSpace: "pre-wrap", marginTop: 6 }}>{r.body}</div>
            </details>
          ))}
        </div>
      </div>
    </>
  );
}
