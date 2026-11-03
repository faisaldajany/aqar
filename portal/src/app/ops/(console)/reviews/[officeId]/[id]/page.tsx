"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api, ApiError, PURPOSE_LABELS } from "@/lib/api";
import { FLAG_LABELS, ISSUE_LABELS, scorePill, when, type OpsConversation, type TranscriptMessage } from "@/lib/ops";

const WHO: Record<TranscriptMessage["sender"], string> = { CUSTOMER: "العميل", AGENT: "الوكيل", HUMAN: "موظف المكتب", SYSTEM: "النظام" };
const OUTCOME: Record<string, string> = {
  replied: "رد", fallback: "رد احتياطي", error: "خطأ", voice_note: "رسالة صوتية", plan_limit: "حد الباقة", capped: "حد يومي", spam: "تكرار من العميل",
};

function short(v: unknown, max = 600) {
  const s = typeof v === "string" ? v : JSON.stringify(v, null, 1);
  return s.length > max ? `${s.slice(0, max)}…` : s;
}

export default function OpsConversationPage() {
  const { officeId, id } = useParams<{ officeId: string; id: string }>();
  const [c, setC] = useState<OpsConversation | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api<OpsConversation>(`/ops/offices/${officeId}/conversations/${id}`).then(setC)
      .catch((e) => setError(e instanceof ApiError ? e.message : "تعذّر تحميل المحادثة."));
  }, [officeId, id]);

  if (!c) return <p className="muted">{error || "جارٍ التحميل…"}</p>;
  const issuesByTurn = new Map<number, { type: string; detail: string }[]>();
  for (const i of c.review?.issues ?? []) issuesByTurn.set(i.turn, [...(issuesByTurn.get(i.turn) ?? []), i]);
  const flagsByTurn = new Map<number, string[]>();
  for (const f of c.flagList) if (f.n != null) flagsByTurn.set(f.n, [...(flagsByTurn.get(f.n) ?? []), FLAG_LABELS[f.code] ?? f.code]);

  return (
    <>
      <p className="small"><Link href="/ops/reviews">← مراجعة الردود</Link></p>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1><bdi dir="rtl">{c.customer}</bdi> · {c.office}</h1>
        <span className="small muted">من {when(c.createdAt)} إلى {when(c.lastMessageAt)}</span>
      </div>

      <div className="grid2" style={{ alignItems: "stretch" }}>
        <section className="card" style={{ alignSelf: "stretch" }}>
          <h2>تقييم الذكاء الاصطناعي</h2>
          {!c.review ? <p className="small muted">لم تُراجع بعد. شغّل المراجعة من صفحة «مراجعة الردود».</p> : (
            <>
              <p><span className={`pill ${scorePill(c.review.score)}`}>{c.review.score} / 5</span> {c.review.summary}</p>
              {c.review.stale && <p className="small muted">رُوجعت قبل رسائل جديدة في المحادثة.</p>}
              {c.review.issues.length > 0 && (
                <ul className="small" style={{ margin: 0, paddingInlineStart: 18 }}>
                  {c.review.issues.map((i, k) => <li key={k}><a href={`#m${i.turn}`}>رسالة {i.turn}</a> · <b>{ISSUE_LABELS[i.type] ?? i.type}</b>: {i.detail}</li>)}
                </ul>
              )}
              <p className="small muted" style={{ marginBottom: 0 }}>{c.review.model} · {when(c.review.createdAt)}</p>
            </>
          )}
        </section>
        <section className="card" style={{ alignSelf: "stretch" }}>
          <h2>ملاحظات القواعد</h2>
          {c.flagList.length === 0 ? <p className="small muted">لا ملاحظات.</p> : (
            <ul className="small" style={{ margin: 0, paddingInlineStart: 18 }}>
              {c.flagList.map((f, k) => <li key={k}>{f.n != null && <><a href={`#m${f.n}`}>رسالة {f.n}</a> · </>}<b>{FLAG_LABELS[f.code] ?? f.code}</b>{f.detail ? `: ${f.detail}` : ""}</li>)}
            </ul>
          )}
          <h3 className="small muted" style={{ fontFamily: "var(--f-body)", marginTop: 12 }}>ما سجّله الوكيل</h3>
          {c.lead ? (
            <p className="small" style={{ margin: 0 }}>
              {[c.lead.purpose ? PURPOSE_LABELS[String(c.lead.purpose)] ?? c.lead.purpose : null, c.lead.propertyType, c.lead.districts,
                c.lead.budgetMax ? `حتى ${Number(c.lead.budgetMax).toLocaleString("en-US")} ريال` : null, c.lead.payment, c.lead.timeline]
                .filter(Boolean).join(" · ") || "—"}
            </p>
          ) : <p className="small muted" style={{ margin: 0 }}>لا شيء.</p>}
          <p className="small muted" style={{ marginBottom: 0 }}>
            {c.handoffs.length > 0 ? `حُوّل لمسوّق ${c.handoffs.length} مرة` : "لم يُحوّل"} · {c.bookings > 0 ? `${c.bookings} موعد` : "لا مواعيد"}
          </p>
        </section>
      </div>

      <section className="card" aria-label="المحادثة" style={{ background: "var(--chat-bg)" }}>
        <div className="msgs" style={{ maxHeight: "none" }}>
          {c.messages.map((m) => {
            const issues = issuesByTurn.get(m.n) ?? [];
            const flags = flagsByTurn.get(m.n) ?? [];
            const marked = issues.length > 0 || flags.length > 0;
            return (
              <div key={m.n} id={`m${m.n}`} className={`msg m-${m.sender}`} style={marked ? { outline: "2px solid var(--viz-2)", outlineOffset: 2 } : undefined}>
                <span className="who">{m.n} · {WHO[m.sender]}</span>
                {m.text ? <div>{m.text}</div> : <div className="muted">[{m.type}]</div>}
                {m.run && (
                  <details className="small" style={{ marginTop: 6 }}>
                    <summary className="muted">{OUTCOME[m.run.outcome] ?? m.run.outcome} · {(m.run.latencyMs / 1000).toFixed(1)} ث{m.run.tools.length ? ` · ${m.run.tools.map((t) => t.name).join("، ")}` : ""}</summary>
                    {m.run.error && <p style={{ color: "var(--bad)", margin: "4px 0" }}>{m.run.error}</p>}
                    {m.run.tools.map((t, k) => (
                      <div key={k} style={{ marginTop: 4 }}>
                        <b dir="ltr">{t.name}</b>
                        <pre dir="ltr" style={{ whiteSpace: "pre-wrap", fontSize: 11, margin: "2px 0", maxHeight: 220, overflow: "auto" }}>{short(t.args, 300)}{"\n→ "}{short(t.result)}</pre>
                      </div>
                    ))}
                  </details>
                )}
                {flags.length > 0 && <div className="row" style={{ gap: 4, marginTop: 6 }}>{flags.map((f) => <span key={f} className="pill p-warn">{f}</span>)}</div>}
                {issues.map((i, k) => <p key={k} className="small" style={{ margin: "6px 0 0", color: "var(--bad)" }}><b>{ISSUE_LABELS[i.type] ?? i.type}:</b> {i.detail}</p>)}
                <time>{when(m.at)}</time>
              </div>
            );
          })}
        </div>
      </section>
    </>
  );
}
