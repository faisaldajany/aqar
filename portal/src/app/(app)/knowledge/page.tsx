"use client";

import Link from "next/link";
import { FormEvent, useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";

type Fact = { id: string; question: string; answer: string; createdAt: string };
type Gap = { conversationId: string; at: string; kind: "HANDOFF" | "FALLBACK"; summary: string | null; question: string | null; customer: string };

const when = (iso: string) =>
  new Date(iso).toLocaleDateString("ar-SA-u-ca-gregory-nu-latn", { day: "numeric", month: "long", timeZone: "Asia/Riyadh" });

/** Questions the agent could not answer, and the office's answers it uses from now on. */
export default function KnowledgePage() {
  const [facts, setFacts] = useState<Fact[] | null>(null);
  const [gaps, setGaps] = useState<Gap[] | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const question = useRef<HTMLInputElement>(null);
  const answer = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    api<Fact[]>("/agent/facts").then(setFacts).catch(() => setError("تعذّر التحميل."));
    api<Gap[]>("/agent/gaps?days=30").then(setGaps).catch(() => setGaps([]));
  }, []);

  async function add(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const f = new FormData(form);
    setBusy(true);
    setError("");
    try {
      setFacts(await api<Fact[]>("/agent/facts", { method: "POST", json: { question: f.get("question"), answer: f.get("answer") } }));
      form.reset();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر الحفظ.");
    } finally {
      setBusy(false);
    }
  }

  function answerThis(g: Gap) {
    if (question.current) question.current.value = g.question ?? "";
    answer.current?.focus();
    answer.current?.scrollIntoView({ behavior: "smooth", block: "center" });
  }

  return (
    <>
      <h1>معلومات للوكيل</h1>
      <p className="small muted">
        ما يسأل عنه العملاء ولا تجيب عنه بيانات العقارات: الدوام، طريقة المعاينة، العمولة، الخدمات في مشروع معيّن. اكتب جواب المكتب مرة،
        ويجيب منه الوكيل بدل أن يحوّل العميل. لا يضيف عليه ولا يخترع غيره.
      </p>

      <section className="card" aria-labelledby="gaps-h" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <h2 id="gaps-h">أسئلة لم يعرف الوكيل جوابها <span className="small muted">(آخر 30 يوماً)</span></h2>
        {gaps === null ? <p className="muted small">جارٍ التحميل…</p> : gaps.length === 0 ? (
          <p className="small muted">لا شيء الآن. حين يحوّل الوكيل عميلاً لأنه لا يعرف الجواب، يظهر سؤاله هنا.</p>
        ) : (
          <ul className="gaplist">
            {gaps.map((g, i) => (
              <li key={`${g.conversationId}-${i}`}>
                <div>
                  <b>{g.question ? `«${g.question}»` : <span className="muted">بلا نص (رسالة صوتية أو صورة)</span>}</b>
                  <div className="small muted">
                    {g.customer} · {when(g.at)} · {g.kind === "FALLBACK" ? "تعذّر على الوكيل الرد فحوّله" : "حوّله الوكيل لأنه لا يعرف الجواب"}
                  </div>
                </div>
                <div className="row">
                  {g.question && <button type="button" className="btn" onClick={() => answerThis(g)}>أضف الجواب</button>}
                  <Link className="btn" href={`/inbox?c=${g.conversationId}`}>المحادثة</Link>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <form className="card" onSubmit={add} style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <h2>أضف معلومة</h2>
        <label className="f" htmlFor="q">السؤال <span className="small muted">كما يسأله العميل</span>
          <input id="q" ref={question} name="question" maxLength={300} placeholder="مثل: هل تأخذون عمولة من المستأجر؟" required />
        </label>
        <label className="f" htmlFor="a">جواب المكتب
          <textarea id="a" ref={answer} name="answer" maxLength={600} placeholder="مثل: العمولة 2.5٪ من قيمة العقد السنوي، تُدفع عند توقيع العقد." required />
        </label>
        <div className="row">
          <button className="btn primary" disabled={busy}>{busy ? "جارٍ الحفظ…" : "احفظ"}</button>
          <span className="small muted">{facts ? `${facts.length} من 40` : ""}</span>
        </div>
        {error && <p className="err" role="alert">{error}</p>}
      </form>

      {facts && facts.length > 0 && (
        <section aria-labelledby="facts-h" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          <h2 id="facts-h">ما يعرفه الوكيل عن المكتب</h2>
          <ul className="gaplist">
            {facts.map((f) => (
              <li key={f.id}>
                <div>
                  <b>{f.question}</b>
                  <div className="small">{f.answer}</div>
                </div>
                <button type="button" className="btn" onClick={async () => setFacts(await api<Fact[]>(`/agent/facts/${f.id}`, { method: "DELETE" }))}>حذف</button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}
