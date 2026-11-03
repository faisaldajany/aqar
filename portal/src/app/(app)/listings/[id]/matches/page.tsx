"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api, ApiError, customersAr, fmt } from "@/lib/api";

type Match = {
  conversationId: string; name: string | null; phone: string | null; need: string; budgetMax: number | null;
  temperature: "HOT" | "WARM" | "COLD"; why: string[]; alreadySent: boolean; lastAlertAt: string | null;
  windowOpen: boolean; suggested: boolean;
};
type Data = {
  listing: { id: string; title: string; district: string; propertyType: string; purpose: "SALE" | "RENT"; price: number; status: string };
  matches: Match[];
};
type Result = { conversationId: string; sent: boolean; via: "TEXT" | "TEMPLATE" | null; error: string | null };

const TEMP: Record<Match["temperature"], [string, string]> = { HOT: ["جاد", "p-ok"], WARM: ["مهتم", "p-warn"], COLD: ["يستكشف", "p-idle"] };

/** Customers a listing fits, picked and messaged in one go. */
export default function ListingMatches() {
  const { id } = useParams<{ id: string }>();
  const [data, setData] = useState<Data | null>(null);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [results, setResults] = useState<Record<string, Result>>({});
  const [summary, setSummary] = useState("");

  useEffect(() => {
    api<Data>(`/listings/${id}/matches`).then((d) => {
      setData(d);
      setPicked(new Set(d.matches.filter((m) => m.suggested).map((m) => m.conversationId)));
    }).catch((e) => setError(e instanceof ApiError ? e.message : "تعذّر التحميل."));
  }, [id]);

  function toggle(cid: string) {
    setPicked((p) => {
      const n = new Set(p);
      if (n.has(cid)) n.delete(cid); else n.add(cid);
      return n;
    });
  }

  async function send() {
    setBusy(true);
    setError("");
    setSummary("");
    try {
      const r = await api<{ sent: number; results: Result[]; matches: Match[] }>(`/listings/${id}/matches/send`, {
        method: "POST", json: { conversationIds: [...picked] },
      });
      setResults(Object.fromEntries(r.results.map((x) => [x.conversationId, x])));
      setData((d) => d && { ...d, matches: r.matches });
      setPicked(new Set(r.results.filter((x) => !x.sent && !x.error?.includes("من قبل")).map((x) => x.conversationId)));
      const failed = r.results.length - r.sent;
      setSummary(`أُرسل إلى ${customersAr(r.sent)}${failed ? `، وتعذّر ${failed} (السبب بجانب كل عميل)` : ""}. الردود تصل إلى المحادثات، ويكمل الوكيل معهم.`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر الإرسال.");
    } finally {
      setBusy(false);
    }
  }

  if (!data) return <p className="muted">{error || "جارٍ التحميل…"}</p>;
  const l = data.listing;
  const outside = data.matches.filter((m) => picked.has(m.conversationId) && !m.windowOpen).length;

  return (
    <>
      <p className="small"><Link href="/listings">العقارات</Link> ←</p>
      <h1>عملاء ينتظرون عقاراً مثل هذا</h1>
      <div className="card">
        <b>{l.title}</b>
        <div className="small muted">{l.propertyType} {l.purpose === "SALE" ? "للبيع" : "للإيجار"} · {l.district} · {fmt(l.price)} ريال</div>
      </div>

      {data.matches.length === 0 ? (
        <section className="card emptystate">
          <h2>لا عملاء مطابقون الآن</h2>
          <p className="muted">نطابق العقار مع من طلب النوع نفسه للغرض نفسه في هذا الحي، وميزانيته تكفي (أو تقل 10٪ على الأكثر). يظهر هنا من يطلب مثله مستقبلاً.</p>
        </section>
      ) : (
        <>
          <ul className="matchlist">
            {data.matches.map((m) => {
              const r = results[m.conversationId];
              const [t, tc] = TEMP[m.temperature];
              return (
                <li key={m.conversationId} className={m.alreadySent ? "sent" : ""}>
                  <label className="check">
                    <input type="checkbox" checked={picked.has(m.conversationId)} disabled={m.alreadySent || busy}
                           onChange={() => toggle(m.conversationId)} aria-label={`اختر ${m.name ?? "العميل"}`} />
                    <span className="who">
                      <Link href={`/inbox?c=${m.conversationId}`}><b>{m.name ?? "عميل بلا اسم"}</b></Link>
                      {m.phone && <bdi className="mono small muted">{m.phone}</bdi>}
                    </span>
                  </label>
                  <div className="small">
                    <span className={`pill ${tc}`}>{t}</span> {m.why.join(" · ")}
                  </div>
                  <div className="small muted">
                    {m.alreadySent ? "وصله هذا العقار" : m.windowOpen ? "يُرسل كرسالة عادية (راسلنا خلال 24 ساعة)" : "يُرسل بقالب «عقار جديد»"}
                    {!m.alreadySent && m.lastAlertAt && " · وصله عقار آخر قبل أيام"}
                  </div>
                  {r && <div className={r.sent ? "small ok" : "small err"} role="status">{r.sent ? "أُرسل ✓" : r.error}</div>}
                </li>
              );
            })}
          </ul>
          <div className="card" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            <div className="row">
              <button className="btn primary" disabled={busy || picked.size === 0} onClick={() => void send()}>
                {busy ? "جارٍ الإرسال…" : `أرسل إلى ${customersAr(picked.size)}`}
              </button>
              {summary && <span className="small" role="status">{summary}</span>}
            </div>
            <p className="small muted">
              تصلهم رسالة باسم المكتب فيها العقار وسعره، ويكمل الوكيل معهم عند الرد. من راسلكم قبل أكثر من 24 ساعة تصله رسالة قالب تعتمده Meta
              {outside > 0 ? ` (${outside} من المختارين)` : ""}، وتُحسب رسالة تسويقية على حساب واتساب للمكتب. من يرد «إيقاف» لا تصله رسائل أخرى.
            </p>
            {error && <p className="err" role="alert">{error}</p>}
          </div>
        </>
      )}
    </>
  );
}
