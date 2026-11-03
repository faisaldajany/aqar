"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { BarList } from "@/components/Charts";
import { api, ApiError, fmt } from "@/lib/api";
import { FLAG_LABELS, ISSUE_LABELS, scorePill, when, type OpsReviews, type ReviewRow, type ReviewRun } from "@/lib/ops";

type Show = "PROBLEMS" | "ALL" | "UNREVIEWED";
const SHOW: [Show, string][] = [["PROBLEMS", "فيها ملاحظات"], ["UNREVIEWED", "لم يراجعها الذكاء الاصطناعي"], ["ALL", "الكل"]];

function iso(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export default function OpsReviewsPage() {
  const today = useMemo(() => new Date(), []);
  const [officeId, setOfficeId] = useState("");
  const [from, setFrom] = useState(iso(new Date(today.getTime() - 6 * 86400000)));
  const [to, setTo] = useState(iso(today));
  const [data, setData] = useState<OpsReviews | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [show, setShow] = useState<Show>("PROBLEMS");
  const [sample, setSample] = useState(10);
  const [progress, setProgress] = useState<{ done: number; target: number; failed: number } | null>(null);
  const [runMsg, setRunMsg] = useState("");

  const query = `${officeId ? `officeId=${officeId}&` : ""}from=${from}&to=${to}`;

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setData(await api<OpsReviews>(`/ops/reviews?${query}`));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر تحميل المحادثات.");
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => { void load(); }, [load]);

  async function runReview() {
    setRunMsg("");
    let done = 0, failed = 0;
    setProgress({ done, target: sample, failed });
    try {
      while (done < sample) {
        const r = await api<ReviewRun>("/ops/reviews/run", { method: "POST",
          json: { officeId: officeId || null, from, to, batch: Math.min(5, sample - done) } });
        done += r.reviewed.length;
        failed += r.failed;
        setProgress({ done, target: sample, failed });
        if (r.reviewed.length === 0) {
          if (r.failed > 0) setRunMsg(`تعذّرت المراجعة: ${r.error ?? "خطأ في النموذج"}`);
          break;
        }
        if (r.remaining === 0) break;
      }
      if (done > 0) setRunMsg(`راجع الذكاء الاصطناعي ${done} محادثة${failed ? `، وتعذّرت ${failed}` : ""}.`);
      else if (!failed) setRunMsg("لا محادثات جديدة للمراجعة في هذه الفترة.");
    } catch (e) {
      setRunMsg(e instanceof ApiError ? e.message : "تعذّرت المراجعة.");
    } finally {
      setProgress(null);
      await load();
    }
  }

  const rows = useMemo(() => (data?.rows ?? []).filter((r: ReviewRow) => {
    if (show === "PROBLEMS") return r.flags.length > 0 || r.review?.verdict === "ISSUE";
    if (show === "UNREVIEWED") return r.agentReplies > 0 && (!r.review || r.review.stale);
    return true;
  }), [data, show]);

  return (
    <>
      <h1>مراجعة الردود</h1>
      <p className="small muted" style={{ marginTop: -8 }}>
        هل يرد الوكيل بشكل صحيح؟ كل محادثة تُفحص بقواعد ثابتة (أخطاء، ردود ناقصة أو بطيئة أو مكررة، عميل طلب موظفاً أو اشتكى)،
        ويمكنك أن تطلب من الذكاء الاصطناعي مراجعة عيّنة منها مقابل نتائج البحث التي رآها الوكيل. بيانات العملاء مخفية: كل عميل برمز،
        وأرقام الجوال والبريد والهوية داخل الرسائل محذوفة.
      </p>

      <div className="card" style={{ display: "flex", flexWrap: "wrap", gap: 10, alignItems: "flex-end" }}>
        <label style={{ display: "flex", flexDirection: "column", gap: 4, minWidth: 200 }}>
          <span className="small muted">المكتب</span>
          <select value={officeId} onChange={(e) => setOfficeId(e.target.value)}>
            <option value="">كل المكاتب</option>
            {data?.offices.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
          </select>
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4 }}><span className="small muted">من</span><input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4 }}><span className="small muted">إلى</span><input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></label>
        <div className="row" style={{ marginInlineStart: "auto" }}>
          <a className="btn" href={`/api/ops/export?${query}&format=csv`}>تصدير CSV</a>
          <a className="btn" href={`/api/ops/export?${query}&format=jsonl`}>تصدير JSON</a>
        </div>
      </div>

      {!data ? <p className="muted">{error || "جارٍ التحميل…"}</p> : (
        <div style={{ display: "flex", flexDirection: "column", gap: 12, opacity: loading ? 0.6 : 1 }}>
          {error && <p className="small" role="alert">{error}</p>}
          <section className="kpis" aria-label="ملخص المراجعة">
            <div className="kpi"><b>{fmt(data.conversations)}</b><span>محادثة عميل، {fmt(data.withAgentReplies)} فيها ردود للوكيل</span></div>
            <div className="kpi"><b>{fmt(data.flagged)}</b><span>عليها ملاحظات من القواعد</span></div>
            <div className="kpi"><b>{fmt(data.reviewed)}</b><span>راجعها الذكاء الاصطناعي، {fmt(data.issues)} فيها مشكلة</span></div>
            <div className="kpi"><b>{data.avgScore == null ? "—" : `${data.avgScore} / 5`}</b><span>متوسط تقييم الردود</span></div>
          </section>

          <section className="card" style={{ display: "flex", flexWrap: "wrap", gap: 10, alignItems: "center" }} aria-label="مراجعة بالذكاء الاصطناعي">
            <span>راجع بالذكاء الاصطناعي</span>
            <select value={sample} onChange={(e) => setSample(Number(e.target.value))} aria-label="عدد المحادثات" style={{ width: "auto" }}>
              {[10, 25, 50, 100].map((n) => <option key={n} value={n}>{n} محادثة</option>)}
            </select>
            <button type="button" className="btn primary" disabled={!!progress || data.toReview === 0} onClick={() => void runReview()}>
              {progress ? `جارٍ المراجعة… ${progress.done} من ${progress.target}` : "ابدأ المراجعة"}
            </button>
            <span className="small muted">
              {data.toReview === 0 ? "كل المحادثات في هذه الفترة مراجَعة." : `${fmt(data.toReview)} محادثة لم تُراجع بعد؛ المحادثات التي عليها ملاحظات أولاً.`}
            </span>
            {runMsg && <span className="small" role="status">{runMsg}</span>}
          </section>

          {(Object.keys(data.byFlag).length > 0 || Object.keys(data.byIssue).length > 0) && (
            <div className="grid2" style={{ alignItems: "stretch" }}>
              <section className="card" style={{ alignSelf: "stretch" }}>
                <h2>ملاحظات القواعد</h2>
                {Object.keys(data.byFlag).length === 0 ? <p className="small muted">لا ملاحظات.</p> : (
                  <BarList label="ملاحظات القواعد" color="var(--viz-2)" rows={Object.entries(data.byFlag).sort((a, b) => b[1] - a[1])
                    .map(([k, v]) => ({ name: FLAG_LABELS[k] ?? k, value: v }))} />
                )}
              </section>
              <section className="card" style={{ alignSelf: "stretch" }}>
                <h2>ما وجده الذكاء الاصطناعي</h2>
                {Object.keys(data.byIssue).length === 0 ? <p className="small muted">{data.reviewed === 0 ? "لم تُراجع محادثات بعد." : "لا مشكلات."}</p> : (
                  <BarList label="مشكلات الردود" rows={Object.entries(data.byIssue).sort((a, b) => b[1] - a[1])
                    .map(([k, v]) => ({ name: ISSUE_LABELS[k] ?? k, value: v }))} />
                )}
              </section>
            </div>
          )}

          <div className="row" role="radiogroup" aria-label="عرض">
            {SHOW.map(([s, label]) => (
              <button key={s} type="button" role="radio" aria-checked={show === s} className={`btn${show === s ? " primary" : ""}`} onClick={() => setShow(s)}>{label}</button>
            ))}
          </div>

          <div className="tbl"><table>
            <thead><tr><th>العميل</th><th>آخر رسالة</th><th>الرسائل</th><th>ملاحظات القواعد</th><th>تقييم الذكاء الاصطناعي</th><th /></tr></thead>
            <tbody>
              {rows.length === 0 && <tr><td colSpan={6} className="muted">لا محادثات مطابقة.</td></tr>}
              {rows.map((r) => (
                <tr key={r.conversationId}>
                  <td><b><bdi dir="rtl">{r.customer}</bdi></b><br /><span className="small muted">{r.office}</span></td>
                  <td className="small">{when(r.lastMessageAt)}</td>
                  <td className="small">{r.messages} رسالة، {r.agentReplies} رد للوكيل{r.handedOver ? " · حُوّل لمسوّق" : ""}</td>
                  <td>{r.flags.length === 0 ? <span className="small muted">—</span> : (
                    <span className="row" style={{ gap: 4 }}>{r.flags.map((f) => <span key={f} className="pill p-warn">{FLAG_LABELS[f] ?? f}</span>)}</span>
                  )}</td>
                  <td className="small" style={{ maxWidth: 320 }}>
                    {r.review ? (
                      <>
                        <span className={`pill ${scorePill(r.review.score)}`}>{r.review.score} / 5</span>{r.review.stale && <span className="muted"> (قبل رسائل جديدة)</span>}
                        <br />{r.review.summary}
                      </>
                    ) : <span className="muted">—</span>}
                  </td>
                  <td><Link className="btn" href={`/ops/reviews/${r.officeId}/${r.conversationId}`}>افتح</Link></td>
                </tr>
              ))}
            </tbody>
          </table></div>
          {data.rows.length >= 500 && <p className="small muted">تظهر أول 500 محادثة. ضيّق الفترة أو اختر مكتباً.</p>}
        </div>
      )}
    </>
  );
}
