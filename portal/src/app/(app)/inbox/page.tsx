"use client";

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, timeAr, windowOpen, type Conversation, type Message } from "@/lib/api";

const WHO: Record<Message["sender"], string> = { CUSTOMER: "العميل", AGENT: "الوكيل", HUMAN: "المكتب", SYSTEM: "النظام" };

export default function InboxPage() {
  const [list, setList] = useState<Conversation[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [error, setError] = useState("");
  const [text, setText] = useState("");
  const bottom = useRef<HTMLDivElement>(null);

  const loadList = useCallback(async () => {
    const l = await api<Conversation[]>("/conversations");
    setList(l);
    setActive((a) => a ?? l[0]?.id ?? null);
  }, []);

  const loadThread = useCallback(async (id: string) => {
    setMessages(await api<Message[]>(`/conversations/${id}/messages`));
  }, []);

  useEffect(() => {
    void loadList().catch(() => setError("تعذّر تحميل المحادثات."));
    const t = setInterval(() => void loadList().catch(() => undefined), 5000);
    return () => clearInterval(t);
  }, [loadList]);

  useEffect(() => {
    if (!active) return;
    void loadThread(active);
    const t = setInterval(() => void loadThread(active).catch(() => undefined), 4000);
    return () => clearInterval(t);
  }, [active, loadThread]);

  useEffect(() => bottom.current?.scrollIntoView({ block: "end" }), [messages.length]);

  const conv = list.find((c) => c.id === active);

  async function toggleAgent() {
    if (!conv) return;
    await api(`/conversations/${conv.id}/${conv.agentActive ? "takeover" : "resume"}`, { method: "POST" });
    await loadList();
  }

  async function send(e: FormEvent) {
    e.preventDefault();
    if (!conv || !text.trim()) return;
    setError("");
    try {
      await api(`/conversations/${conv.id}/messages`, { method: "POST", json: { text } });
      setText("");
      await Promise.all([loadThread(conv.id), loadList()]);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر الإرسال.");
    }
  }

  return (
    <>
      <h1>المحادثات</h1>
      {error && <p className="err" role="alert">{error}</p>}
      <div className="inbox">
        <div className="list">
          {list.length === 0 && <p className="small muted" style={{ padding: 14 }}>لا محادثات بعد. تظهر هنا رسائل العملاء فور وصولها.</p>}
          {list.map((c) => (
            <button key={c.id} className="item" aria-current={c.id === active} onClick={() => setActive(c.id)}>
              <span className="row" style={{ justifyContent: "space-between" }}>
                <b>{c.name || c.waId}</b>
                <span className={`pill ${c.agentActive ? "p-acc" : "p-ok"}`}>{c.agentActive ? "يرد الوكيل" : "المكتب يرد"}</span>
              </span>
              <span className="last">{c.lastBody ?? ""}</span>
            </button>
          ))}
        </div>
        <div className="thread">
          {conv ? (
            <>
              <div className="thread-head">
                <div><b>{conv.name || "عميل"}</b> <span className="mono small muted">{conv.waId}</span></div>
                <button className="btn" onClick={toggleAgent}>{conv.agentActive ? "تولَّ المحادثة" : "أعدها للوكيل"}</button>
              </div>
              <div className="msgs">
                {messages.map((m) => (
                  <div key={m.id} className={`msg m-${m.sender}`}>
                    <span className="who">{WHO[m.sender]}</span>
                    {m.body ?? (m.type === "audio" ? "رسالة صوتية" : `[${m.type}]`)}
                    <time>{timeAr(m.createdAt)}</time>
                  </div>
                ))}
                <div ref={bottom} />
              </div>
              <form className="compose" onSubmit={send}>
                <input type="text" aria-label="اكتب رداً" value={text} onChange={(e) => setText(e.target.value)}
                  placeholder={windowOpen(conv.lastInboundAt) ? "اكتب رداً باسم المكتب" : "انتهت نافذة 24 ساعة: لا يُسمح إلا بقالب معتمد"}
                  disabled={!windowOpen(conv.lastInboundAt)} />
                <button className="btn primary" disabled={!windowOpen(conv.lastInboundAt) || !text.trim()}>إرسال</button>
              </form>
            </>
          ) : (
            <p className="muted" style={{ padding: 16 }}>اختر محادثة.</p>
          )}
        </div>
      </div>
    </>
  );
}
