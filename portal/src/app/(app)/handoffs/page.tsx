"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api, timeAr, type Handoff } from "@/lib/api";

const TABS = [["OPEN", "جديدة"], ["CLAIMED", "مع مسوّق"], ["CLOSED", "مغلقة"]] as const;

/** Every customer the agent handed over, with the 3-line summary the marketer got on WhatsApp. */
export default function HandoffsPage() {
  const [tab, setTab] = useState<(typeof TABS)[number][0]>("OPEN");
  const [list, setList] = useState<Handoff[]>([]);
  const [error, setError] = useState("");

  const load = useCallback(() => api<Handoff[]>(`/handoffs?status=${tab}`).then(setList).catch(() => setError("تعذّر التحميل.")), [tab]);
  useEffect(() => {
    void load();
    const t = setInterval(() => void load(), 20000);
    return () => clearInterval(t);
  }, [load]);

  async function act(h: Handoff, action: "claim" | "close") {
    await api(`/handoffs/${h.id}/${action}`, { method: "POST" });
    void load();
  }

  return (
    <>
      <h1>التحويلات للمسوّقين</h1>
      <p className="small muted">يحوّل الوكيل العميل الجاد أو الطلب الذي لا يستطيع خدمته، ويرسل للمسوّق ملخصاً من ثلاثة أسطر.</p>
      <div className="row" role="tablist">
        {TABS.map(([k, label]) => (
          <button key={k} role="tab" aria-selected={tab === k} className={`btn${tab === k ? " primary" : ""}`} onClick={() => setTab(k)}>{label}</button>
        ))}
      </div>
      {error && <p className="err">{error}</p>}
      <div className="card">
        {list.length === 0 ? <p className="muted small">لا شيء هنا.</p> : list.map((h) => (
          <div key={h.id} className="row" style={{ justifyContent: "space-between", alignItems: "flex-start", borderBottom: "1px solid var(--line)", padding: "10px 0" }}>
            <div style={{ minWidth: 0 }}>
              <b>{h.customer}</b> <span className="small muted">· {timeAr(h.createdAt)}{h.marketer ? ` · ${h.marketer}` : ""}</span>
              <div className="small" style={{ whiteSpace: "pre-wrap", marginTop: 4 }}>{h.summary}</div>
            </div>
            <span className="row">
              <Link className="btn" href={`/inbox?c=${h.conversationId}`}>المحادثة</Link>
              {h.status === "OPEN" && <button className="btn" onClick={() => act(h, "claim")}>استلمها مسوّق</button>}
              {h.status !== "CLOSED" && <button className="btn" onClick={() => act(h, "close")}>أغلق</button>}
            </span>
          </div>
        ))}
      </div>
    </>
  );
}
