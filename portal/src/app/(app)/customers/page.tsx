"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { FormEvent, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError, latinDigits } from "@/lib/api";

type Stage = "NEW" | "CONTACTED" | "VIEWING" | "WON" | "LOST";
type Temperature = "HOT" | "WARM" | "COLD";

type Customer = {
  conversationId: string; name: string | null; phone: string | null; purpose: "SALE" | "RENT" | null; propertyType: string | null;
  districts: string | null; budgetMin: number | null; budgetMax: number | null; payment: string | null; timeline: string | null;
  notes: string | null; temperature: Temperature; stage: Stage; stageAuto: boolean; stageAt: string | null; dealValue: number | null;
  lostReason: string | null; marketerId: string | null; marketer: string | null; createdAt: string; lastMessageAt: string | null;
  bookings: number;
};
type Page = {
  customers: Customer[]; total: number; stages: Record<"ALL" | Stage, number>; types: string[];
  marketers: { id: string; name: string }[]; truncated: boolean;
};

const STAGES: [Stage | "ALL", string][] = [
  ["ALL", "الكل"], ["NEW", "جديد"], ["CONTACTED", "تواصلنا"], ["VIEWING", "عاين"], ["WON", "تمت الصفقة"], ["LOST", "لم تتم"],
];
const STAGE_LABEL: Record<Stage, string> = { NEW: "جديد", CONTACTED: "تواصلنا", VIEWING: "عاين", WON: "تمت الصفقة", LOST: "لم تتم" };
const TEMP: Record<Temperature, [string, string]> = { HOT: ["جاد", "p-ok"], WARM: ["مهتم", "p-warn"], COLD: ["يستكشف", "p-idle"] };
const PAYMENT: Record<string, string> = { CASH: "كاش", FINANCE: "تمويل", INSTALLMENTS: "أقساط" };

const sarFmt = (n: number) => `${Math.round(n).toLocaleString("en-US")} ريال`;
function budget(c: Customer): string {
  if (c.budgetMax == null && c.budgetMin == null) return "";
  if (c.budgetMin != null && c.budgetMax != null && c.budgetMin !== c.budgetMax) {
    return `${Math.round(c.budgetMin).toLocaleString("en-US")} – ${sarFmt(c.budgetMax)}`;
  }
  return `حتى ${sarFmt((c.budgetMax ?? c.budgetMin)!)}`;
}
function ago(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  const day = (x: Date) => x.toLocaleDateString("en-CA", { timeZone: "Asia/Riyadh" });
  const today = day(new Date()), that = day(d);
  if (that === today) return `اليوم ${d.toLocaleTimeString("ar-SA-u-nu-latn", { hour: "numeric", minute: "2-digit", timeZone: "Asia/Riyadh" })}`;
  const days = Math.round((new Date(today).getTime() - new Date(that).getTime()) / 86_400_000);
  if (days === 1) return "أمس";
  if (days < 7) return `قبل ${days} أيام`;
  return d.toLocaleDateString("ar-SA-u-ca-gregory-nu-latn", { day: "numeric", month: "long", timeZone: "Asia/Riyadh" });
}

export default function CustomersPage() {
  return <Suspense fallback={null}><Customers /></Suspense>;
}

function Customers() {
  const params = useSearchParams();
  const [stage, setStage] = useState<Stage | "ALL">((params.get("stage") as Stage) || "ALL");
  const [f, setF] = useState({
    q: "", temperature: params.get("temperature") ?? "", purpose: "", type: "", district: "", marketer: "", minBudget: "", maxBudget: "",
  });
  const [q, setQ] = useState("");
  const [data, setData] = useState<Page | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [showFilters, setShowFilters] = useState(false);

  // Typing in the search box waits a moment before asking the server.
  useEffect(() => {
    const t = setTimeout(() => setF((x) => (x.q === q ? x : { ...x, q })), 300);
    return () => clearTimeout(t);
  }, [q]);

  const query = useMemo(() => {
    const p = new URLSearchParams();
    Object.entries(f).forEach(([k, v]) => { if (v.trim()) p.set(k, k.endsWith("Budget") ? latinDigits(v).replace(/[^\d]/g, "") : v.trim()); });
    if (stage !== "ALL") p.set("stage", stage);
    return p.toString();
  }, [f, stage]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await api<Page>(`/customers${query ? `?${query}` : ""}`));
      setError("");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر تحميل العملاء.");
    } finally {
      setLoading(false);
    }
  }, [query]);
  useEffect(() => { void load(); }, [load]);

  function replace(c: Customer) {
    setData((d) => d && { ...d, customers: d.customers.map((x) => (x.conversationId === c.conversationId ? c : x)) });
    void load(); // the tab counts, and the row leaves a tab it no longer belongs to
  }

  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const active = Object.entries(f).filter(([k, v]) => k !== "q" && v).length;

  return (
    <>
      <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-end" }}>
        <div>
          <h1>العملاء</h1>
          <p className="small muted" style={{ margin: 0 }}>كل من راسل المكتب، وما يطلبه، وأين وصل. يحدّث حداء المرحلة من المحادثة، وتغيّرها أنت متى شئت.</p>
        </div>
        <a className="btn" href={`/api/customers/export${query ? `?${query}` : ""}`} download>تنزيل Excel</a>
      </div>

      <div className="tabs" role="tablist" aria-label="مرحلة العميل">
        {STAGES.map(([s, label]) => (
          <button key={s} type="button" role="tab" className="tab" aria-selected={stage === s} onClick={() => setStage(s)}>
            {label} <span className="small muted">{data?.stages[s] ?? ""}</span>
          </button>
        ))}
      </div>

      <div className="card custfilters">
        <div className="row">
          <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="ابحث بالاسم أو الجوال أو الحي" aria-label="بحث" style={{ flex: "1 1 220px" }} />
          <select value={f.temperature} onChange={(e) => set("temperature")(e.target.value)} aria-label="الجدية" style={{ flex: "0 1 150px" }}>
            <option value="">كل الجدية</option><option value="HOT">جاد</option><option value="WARM">مهتم</option><option value="COLD">يستكشف</option>
          </select>
          <button type="button" className="btn" aria-expanded={showFilters} onClick={() => setShowFilters((v) => !v)}>
            فلاتر{active > 0 ? ` (${active})` : ""}
          </button>
        </div>
        {showFilters && (
          <div className="custgrid">
            <label className="f">الطلب
              <select value={f.purpose} onChange={(e) => set("purpose")(e.target.value)}>
                <option value="">الكل</option><option value="SALE">شراء</option><option value="RENT">إيجار</option>
              </select>
            </label>
            <label className="f">نوع العقار
              <select value={f.type} onChange={(e) => set("type")(e.target.value)}>
                <option value="">الكل</option>{data?.types.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </label>
            <label className="f">الحي
              <input value={f.district} onChange={(e) => set("district")(e.target.value)} placeholder="مثل الملقا" />
            </label>
            <label className="f">المسوّق
              <select value={f.marketer} onChange={(e) => set("marketer")(e.target.value)}>
                <option value="">الكل</option><option value="none">بلا مسوّق</option>
                {data?.marketers.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
              </select>
            </label>
            <label className="f">الميزانية من
              <input inputMode="numeric" value={f.minBudget} onChange={(e) => set("minBudget")(e.target.value)} placeholder="ريال" />
            </label>
            <label className="f">الميزانية إلى
              <input inputMode="numeric" value={f.maxBudget} onChange={(e) => set("maxBudget")(e.target.value)} placeholder="ريال" />
            </label>
            {active > 0 && (
              <button type="button" className="linkbtn" onClick={() => setF((x) => ({ ...x, temperature: "", purpose: "", type: "", district: "", marketer: "", minBudget: "", maxBudget: "" }))}>
                مسح الفلاتر
              </button>
            )}
          </div>
        )}
      </div>

      {error && <p className="err" role="alert">{error}</p>}
      {!data ? <p className="muted">جارٍ التحميل…</p> : data.customers.length === 0 ? (
        <section className="card emptystate">
          <h2>{data.stages.ALL === 0 && !query ? "لا عملاء بعد" : "لا عملاء مطابقون"}</h2>
          <p className="muted">{data.stages.ALL === 0 && !query
            ? "يظهر هنا كل عميل يراسل رقم المكتب، مع ما عرفه الوكيل عن طلبه وميزانيته."
            : "جرّب تغيير الفلاتر أو البحث."}</p>
        </section>
      ) : (
        <ul className="custlist" style={{ opacity: loading ? 0.6 : 1 }} aria-busy={loading}>
          {data.customers.map((c) => <Row key={c.conversationId} c={c} onSaved={replace} />)}
        </ul>
      )}
      {data && data.total > data.customers.length && (
        <p className="small muted">يظهر أول {data.customers.length} من {data.total}. ضيّق البحث، أو نزّل الملف لترى الكل.</p>
      )}
      <FollowUps />
    </>
  );
}

type FollowUpState = { days: number | null; sentLast30Days: number; templateStatus: string | null };

/** The automatic check-in with serious customers who stopped replying. */
function FollowUps() {
  const [s, setS] = useState<FollowUpState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { api<FollowUpState>("/customers/followups").then(setS).catch(() => undefined); }, []);
  if (!s) return null;

  async function change(v: string) {
    setBusy(true);
    setError("");
    try {
      setS(await api<FollowUpState>("/customers/followups", { method: "PUT", json: { days: v ? Number(v) : null } }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر الحفظ.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card" aria-labelledby="fu-h" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <div className="followups">
        <div>
          <h2 id="fu-h">المتابعة التلقائية</h2>
          <p className="small muted" style={{ margin: 0 }}>
            رسالة واحدة للعميل الجاد أو المهتم إذا توقف عن الرد: «ما زلت تبحث عن …؟». بين 10 صباحاً و8 مساءً، ولا تُرسل إذا تولّى أحد المحادثة أو عنده موعد قادم.
          </p>
        </div>
        <select aria-label="المتابعة التلقائية" value={s.days ?? ""} disabled={busy} onChange={(e) => void change(e.target.value)}>
          <option value="">متوقفة</option>
          {[2, 3, 5, 7, 14].map((d) => <option key={d} value={d}>بعد {d === 2 ? "يومين" : d <= 10 ? `${d} أيام` : `${d} يوماً`} من التوقف</option>)}
        </select>
      </div>
      {s.days != null && s.templateStatus !== "APPROVED" && (
        <p className="small" role="status">تبدأ الرسائل بعد أن تعتمد Meta قالب «المتابعة» على رقمكم{s.templateStatus === "REJECTED" ? " (رُفض: راجع صفحة ربط واتساب)" : " (عادة خلال دقائق إلى ساعات)"}.</p>
      )}
      {s.sentLast30Days > 0 && <p className="small muted">أُرسلت {s.sentLast30Days} متابعة في آخر 30 يوماً.</p>}
      {error && <p className="err" role="alert">{error}</p>}
    </section>
  );
}

function Row({ c, onSaved }: { c: Customer; onSaved: (c: Customer) => void }) {
  const [editing, setEditing] = useState<Stage | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const value = useRef<HTMLInputElement>(null);

  async function save(stage: Stage, extra: { dealValue?: number | null; lostReason?: string | null } = {}) {
    setBusy(true);
    setError("");
    try {
      onSaved(await api<Customer>(`/customers/${c.conversationId}/stage`, { method: "POST", json: { stage, ...extra } }));
      setEditing(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر الحفظ.");
    } finally {
      setBusy(false);
    }
  }

  function pick(s: Stage) {
    if (s === "WON" || s === "LOST") setEditing(s);
    else void save(s);
  }

  function submitDetail(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const v = String(new FormData(e.currentTarget).get("detail") ?? "").trim();
    if (editing === "WON") {
      const n = latinDigits(v).replace(/[^\d.]/g, "");
      void save("WON", { dealValue: n ? Number(n) : null });
    } else if (editing === "LOST") {
      void save("LOST", { lostReason: v || null });
    }
  }

  const need = [c.propertyType, c.purpose === "SALE" ? "للشراء" : c.purpose === "RENT" ? "للإيجار" : null].filter(Boolean).join(" ");
  const [tLabel, tCls] = TEMP[c.temperature];
  return (
    <li className="custrow">
      <div className="who">
        <Link href={`/inbox?c=${c.conversationId}`} className="name">{c.name || "عميل بلا اسم"}</Link>
        {c.phone && <bdi className="mono small muted">{c.phone}</bdi>}
      </div>
      <div className="need small">
        <span>{need || <span className="muted">لم يحدد طلبه بعد</span>}{c.districts ? ` · ${c.districts}` : ""}</span>
        {(budget(c) || c.payment) && <span className="muted">{[budget(c), c.payment ? PAYMENT[c.payment] : null].filter(Boolean).join(" · ")}</span>}
      </div>
      <div className="meta small">
        <span className={`pill ${tCls}`}>{tLabel}</span>
        <span className="muted">{c.marketer ?? "بلا مسوّق"}</span>
        <span className="muted">{ago(c.lastMessageAt ?? c.createdAt)}</span>
      </div>
      <div className="stagecell">
        <select aria-label={`مرحلة ${c.name ?? "العميل"}`} value={editing ?? c.stage} disabled={busy}
                onChange={(e) => pick(e.target.value as Stage)} className={`stage s-${(editing ?? c.stage).toLowerCase()}`}>
          {(Object.keys(STAGE_LABEL) as Stage[]).map((s) => <option key={s} value={s}>{STAGE_LABEL[s]}</option>)}
        </select>
        {!editing && c.stage === "WON" && c.dealValue != null && <span className="small">{sarFmt(c.dealValue)}</span>}
        {!editing && c.stage === "LOST" && c.lostReason && <span className="small muted">{c.lostReason}</span>}
        {!editing && c.stageAuto && <span className="small muted" title="من المحادثة: ردّ أحد من المكتب، أو حُجز موعد أو تمت معاينة">حدّدها حداء</span>}
        {editing && (
          <form className="stagedetail" onSubmit={submitDetail}>
            <input ref={value} name="detail" autoFocus inputMode={editing === "WON" ? "numeric" : "text"}
                   placeholder={editing === "WON" ? "قيمة الصفقة بالريال (اختياري)" : "السبب (اختياري)"} aria-label={editing === "WON" ? "قيمة الصفقة" : "السبب"} />
            <div className="row">
              <button className="btn primary" disabled={busy}>حفظ</button>
              <button type="button" className="btn" disabled={busy} onClick={() => setEditing(null)}>إلغاء</button>
            </div>
          </form>
        )}
        {error && <span className="err" role="alert">{error}</span>}
      </div>
    </li>
  );
}
