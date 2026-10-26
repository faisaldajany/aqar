"use client";

import { useSearchParams } from "next/navigation";
import { FormEvent, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, fmt, PURPOSE_LABELS, riyadhDay, riyadhTime, TEMP_LABELS, timeAr, windowOpen, type Conversation, type ConversationDetails, type Marketer, type Message } from "@/lib/api";

const WHO: Record<Message["sender"], string> = { CUSTOMER: "العميل", AGENT: "الوكيل", HUMAN: "المكتب", SYSTEM: "النظام" };

export default function InboxPage() {
  return <Suspense fallback={null}><Inbox /></Suspense>;
}

function Inbox() {
  const params = useSearchParams();
  const [q, setQ] = useState("");
  const [list, setList] = useState<Conversation[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [error, setError] = useState("");
  const [text, setText] = useState("");
  const bottom = useRef<HTMLDivElement>(null);

  const loadList = useCallback(async () => {
    const l = await api<Conversation[]>("/conversations");
    setList(l);
    setActive((a) => a ?? params.get("c") ?? l[0]?.id ?? null);
  }, [params]);

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

  useEffect(() => { bottom.current?.scrollIntoView({ block: "end" }); }, [messages.length]);

  const conv = list.find((c) => c.id === active);
  const needle = q.trim();
  const shown = needle ? list.filter((c) => (c.name ?? "").includes(needle) || c.waId.includes(needle) || (c.lastBody ?? "").includes(needle)) : list;

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
      <div className="inbox with-side">
        <div className="list">
          <input className="search" type="search" aria-label="بحث في المحادثات" placeholder="بحث بالاسم أو الرقم أو النص" value={q} onChange={(e) => setQ(e.target.value)} />
          {list.length === 0 && <p className="small muted" style={{ padding: 14 }}>لا محادثات بعد. تظهر هنا رسائل العملاء فور وصولها.</p>}
          {shown.map((c) => (
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
        {conv && <LeadPanel id={conv.id} onChanged={loadList} />}
      </div>
    </>
  );
}

function LeadPanel({ id, onChanged }: { id: string; onChanged: () => Promise<void> }) {
  const [d, setD] = useState<ConversationDetails | null>(null);
  const [marketers, setMarketers] = useState<Marketer[]>([]);
  const [pick, setPick] = useState("");
  const [erase, setErase] = useState(false);
  const load = useCallback(() => api<ConversationDetails>(`/conversations/${id}/details`).then(setD).catch(() => undefined), [id]);
  useEffect(() => {
    void load();
    const t = setInterval(() => void load(), 10000);
    return () => clearInterval(t);
  }, [load]);
  useEffect(() => { api<Marketer[]>("/marketers").then((m) => setMarketers(m.filter((x) => x.active))).catch(() => undefined); }, []);

  async function assign() {
    if (!pick) return;
    await api(`/conversations/${id}/assign`, { method: "POST", json: { marketerId: pick } });
    setPick("");
    await Promise.all([load(), onChanged()]);
  }

  if (!d) return <aside className="leadside"><p className="small muted">…</p></aside>;
  const l = d.lead;
  const t = l.temperature ? TEMP_LABELS[l.temperature] : null;
  const budget = l.budgetMax ? (l.budgetMin ? `${fmt(l.budgetMin)} – ${fmt(l.budgetMax)}` : `حتى ${fmt(l.budgetMax)}`) : null;
  return (
    <aside className="leadside" aria-label="بطاقة العميل">
      <div>
        <div className="row" style={{ justifyContent: "space-between" }}><h3>بطاقة العميل</h3>{t && <span className={`pill ${t[1]}`}>{t[0]}</span>}</div>
        {Object.keys(l).length === 0 ? <p className="small muted">يجمع الوكيل الطلب أثناء المحادثة.</p> : (
          <dl>
            {l.customerName && <><dt>الاسم</dt><dd>{l.customerName}</dd></>}
            {l.purpose && <><dt>الغرض</dt><dd>{PURPOSE_LABELS[l.purpose] ?? l.purpose}</dd></>}
            {l.propertyType && <><dt>النوع</dt><dd>{l.propertyType}</dd></>}
            {l.districts && <><dt>الأحياء</dt><dd>{l.districts}</dd></>}
            {budget && <><dt>الميزانية</dt><dd>{budget}</dd></>}
            {l.payment && <><dt>الدفع</dt><dd>{l.payment === "CASH" ? "كاش" : l.payment === "FINANCE" ? "تمويل" : l.payment}</dd></>}
            {l.timeline && <><dt>متى</dt><dd>{l.timeline}</dd></>}
            {l.notes && <><dt>ملاحظات</dt><dd>{l.notes}</dd></>}
          </dl>
        )}
      </div>
      {d.bookings.length > 0 && (
        <div>
          <h3>المواعيد</h3>
          {d.bookings.map((b) => <p key={b.id} className="small" style={{ margin: "2px 0" }}>{riyadhDay(b.startsAt)} {riyadhTime(b.startsAt)} · {b.listing}</p>)}
        </div>
      )}
      {d.handoffs.length > 0 && (
        <div>
          <h3>التحويلات</h3>
          {d.handoffs.map((h) => <p key={h.id} className="small" style={{ margin: "2px 0", whiteSpace: "pre-wrap" }}>{h.marketer ? `إلى ${h.marketer}: ` : ""}{h.summary}</p>)}
        </div>
      )}
      <div>
        <h3>حوّل لمسوّق</h3>
        <div className="row">
          <select aria-label="اختر مسوّقاً" value={pick} onChange={(e) => setPick(e.target.value)} style={{ flex: 1 }}>
            <option value="">اختر</option>
            {marketers.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
          </select>
          <button className="btn" disabled={!pick} onClick={assign}>حوّل</button>
        </div>
        <p className="small muted">يتوقف الوكيل في هذه المحادثة حتى تعيدها له.</p>
      </div>
      <div>
        <h3>بيانات العميل</h3>
        {!erase ? <button className="btn" onClick={() => setErase(true)}>حذف بيانات العميل بطلبه</button> : (
          <div className="row">
            <button className="btn" style={{ color: "var(--bad)" }} onClick={async () => {
              await api(`/conversations/${id}/personal-data`, { method: "DELETE" });
              setErase(false);
              await Promise.all([load(), onChanged()]);
            }}>تأكيد الحذف النهائي</button>
            <button className="btn" onClick={() => setErase(false)}>إلغاء</button>
          </div>
        )}
        <p className="small muted">يحذف الرسائل والاسم والطلب نهائياً (نظام حماية البيانات الشخصية). تبقى الأعداد فقط.</p>
      </div>
    </aside>
  );
}
