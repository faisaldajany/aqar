"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { MessageBody } from "@/components/MessageBody";
import { api, ApiError, timeAr, type Message } from "@/lib/api";

const SUGGESTIONS = [
  "السلام عليكم، عندكم شقة للإيجار؟",
  "أبي فيلا بالنرجس حدود مليونين كاش",
  "أبي أعاين بكرة العصر",
  "كم آخر سعر؟",
  "أبي أكلم المسوّق",
];

/** The office plays the customer and chats with its own agent, using its real listings and rules. */
export default function TryAgentPage() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [text, setText] = useState("");
  const [waiting, setWaiting] = useState(false);
  const [error, setError] = useState("");
  const bottom = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    const r = await api<{ messages: Message[] }>("/sandbox");
    setMessages(r.messages);
    const last = r.messages[r.messages.length - 1];
    if (last && last.sender !== "CUSTOMER") setWaiting(false);
    return r.messages;
  }, []);

  useEffect(() => { void load().catch(() => setError("تعذّر تحميل المحادثة.")); }, [load]);
  useEffect(() => {
    if (!waiting) return;
    const t = setInterval(() => void load().catch(() => undefined), 1500);
    const stop = setTimeout(() => { setWaiting(false); setError("لم يصل رد خلال 40 ثانية. حاول مرة أخرى."); }, 40000);
    return () => { clearInterval(t); clearTimeout(stop); };
  }, [waiting, load]);
  useEffect(() => { bottom.current?.scrollIntoView({ block: "end" }); }, [messages.length, waiting]);

  async function send(value: string) {
    if (!value.trim() || waiting) return;
    setError("");
    try {
      await api("/sandbox/messages", { method: "POST", json: { text: value } });
      setText("");
      setWaiting(true);
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر الإرسال.");
    }
  }

  async function reset() {
    await api("/sandbox/reset", { method: "POST" });
    setMessages([]);
    setWaiting(false);
  }

  return (
    <>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <div>
          <h1>جرّب الوكيل</h1>
          <p className="small muted">اكتب كأنك عميل. يرد الوكيل من عقاراتك ومسوّقيك ومواعيدك الحقيقية، ولا يُرسل شيء على واتساب.</p>
        </div>
        {messages.length > 0 && <button className="btn" onClick={reset}>ابدأ محادثة جديدة</button>}
      </div>

      <div className="thread trychat">
        <div className="msgs" aria-live="polite">
          {messages.length === 0 && (
            <div className="tryempty">
              <p>ابدأ بإحدى هذه الرسائل أو اكتب رسالتك:</p>
              <div className="row">
                {SUGGESTIONS.map((s) => <button key={s} className="chip" onClick={() => send(s)}>{s}</button>)}
              </div>
              <p className="small muted">ما عندك عقارات بعد؟ <Link href="/listings">أضف عقاراً</Link> ليجد الوكيل ما يعرضه.</p>
            </div>
          )}
          {messages.map((m) => (
            <div key={m.id} className={`msg m-${m.sender}`}>
              <span className="who">{m.sender === "CUSTOMER" ? "أنت (العميل)" : m.sender === "AGENT" ? "الوكيل" : "المكتب"}</span>
              <MessageBody m={m} />
              <time>{timeAr(m.createdAt)}</time>
            </div>
          ))}
          {waiting && <div className="msg m-AGENT typing" role="status" aria-label="الوكيل يكتب"><i /><i /><i /></div>}
          <div ref={bottom} />
        </div>
        <form className="compose" onSubmit={(e: FormEvent) => { e.preventDefault(); void send(text); }}>
          <input type="text" aria-label="رسالتك كعميل" placeholder="اكتب رسالة كأنك عميل" value={text} onChange={(e) => setText(e.target.value)} maxLength={2000} />
          <button className="btn primary" disabled={!text.trim() || waiting}>إرسال</button>
        </form>
      </div>
      {error && <p className="err" role="alert">{error}</p>}
      <p className="small muted">تظهر هذه المحادثة في «المحادثات» باسم «تجربة المكتب»، وتُحسب ردودها من رصيد الشهر.</p>
    </>
  );
}
