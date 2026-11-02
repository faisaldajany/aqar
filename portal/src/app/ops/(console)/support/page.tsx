"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { when, type OpsSupport } from "@/lib/ops";

export default function OpsSupportPage() {
  const [rows, setRows] = useState<OpsSupport[] | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api<OpsSupport[]>("/ops/support").then(setRows).catch(() => setError("تعذّر التحميل."));
  }, []);

  if (!rows) return <p className="muted">{error || "جارٍ التحميل…"}</p>;
  return (
    <>
      <h1>رسائل الدعم</h1>
      <p className="small muted">آخر 200 رسالة من نموذج التواصل. الرسائل التي لم تُرسل بالبريد تُعاد محاولتها تلقائياً لمدة 3 أيام بعد ضبط البريد.</p>
      {rows.length === 0 && <p className="card muted">لا رسائل بعد.</p>}
      {rows.map((m) => (
        <article key={m.id} className="card" style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <b>{m.name} · {m.topic}</b>
            <span className="small muted">{when(m.createdAt)} · {m.source === "website" ? "الموقع" : "البوابة"}</span>
          </div>
          <div className="row small">
            {m.email && <a href={`mailto:${m.email}`} dir="ltr">{m.email}</a>}
            {m.phone && <a href={`https://wa.me/${m.phone.replace(/\D/g, "").replace(/^0/, "966")}`} dir="ltr">{m.phone}</a>}
            {m.forwarded ? <span className="pill p-ok">أُرسلت بالبريد</span> : <span className="pill p-warn" title={m.forwardError ?? ""}>لم تُرسل بالبريد</span>}
          </div>
          <p style={{ whiteSpace: "pre-wrap", margin: 0 }}>{m.message}</p>
        </article>
      ))}
    </>
  );
}
