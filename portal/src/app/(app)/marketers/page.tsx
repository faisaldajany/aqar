"use client";

import { FormEvent, useEffect, useState } from "react";
import { api, ApiError, type Marketer, type Usage } from "@/lib/api";

/** Marketers receive handoffs for the districts they cover. */
export default function MarketersPage() {
  const [list, setList] = useState<Marketer[]>([]);
  const [usage, setUsage] = useState<Usage | null>(null);
  const [error, setError] = useState("");

  const load = () => {
    api<Marketer[]>("/marketers").then(setList).catch(() => setError("تعذّر التحميل."));
    api<Usage>("/usage").then(setUsage).catch(() => undefined);
  };
  useEffect(load, []);

  async function add(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const f = new FormData(form);
    const raw = String(f.get("phone") ?? "").replace(/\D/g, "");
    const waPhone = raw.startsWith("05") ? "966" + raw.slice(1) : raw.startsWith("5") ? "966" + raw : raw;
    setError("");
    try {
      await api("/marketers", {
        method: "POST",
        json: { name: f.get("name"), waPhone, districts: String(f.get("districts") ?? "").split(/[,،]/).map((s) => s.trim()).filter(Boolean) },
      });
      form.reset();
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّرت الإضافة.");
    }
  }

  return (
    <>
      <h1>المسوّقون</h1>
      <p className="small muted">يحوّل الوكيل العميل الجاد إلى المسوّق المسؤول عن حيّه مع ملخص. {usage && `باقتك تسمح بـ${usage.marketersLimit} مسوّقين.`}</p>
      <div className="tbl">
        <table>
          <thead><tr><th>الاسم</th><th>واتساب</th><th>الأحياء</th><th></th></tr></thead>
          <tbody>
            {list.map((m) => (
              <tr key={m.id}>
                <td>{m.name}</td>
                <td className="mono">{m.waPhone}</td>
                <td>{m.districts.join("، ") || <span className="muted">كل الأحياء</span>}</td>
                <td><button className="btn" onClick={async () => { await api(`/marketers/${m.id}/deactivate`, { method: "POST" }); load(); }}>إيقاف</button></td>
              </tr>
            ))}
            {list.length === 0 && <tr><td colSpan={4} className="muted">لم يُضف مسوّقون بعد.</td></tr>}
          </tbody>
        </table>
      </div>
      <form className="card grid3" onSubmit={add} style={{ alignItems: "end" }}>
        <label className="f" htmlFor="name">الاسم<input id="name" name="name" type="text" required /></label>
        <label className="f" htmlFor="phone">جوال واتساب<input id="phone" name="phone" type="text" dir="ltr" required placeholder="05XXXXXXXX" /></label>
        <label className="f" htmlFor="districts">الأحياء <span className="small muted">مفصولة بفاصلة</span><input id="districts" name="districts" type="text" placeholder="النرجس، الياسمين" /></label>
        <div><button className="btn primary">إضافة مسوّق</button></div>
      </form>
      {error && <p className="err" role="alert">{error}</p>}
    </>
  );
}
