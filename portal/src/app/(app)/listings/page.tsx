"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { api, ApiError, daysUntil, fmt, type AqarDraft, type Listing, type ListingForm } from "@/lib/api";
import ImportWizard from "./ImportWizard";
import ListingFormView from "./ListingFormView";

type Mode = { kind: "list" } | { kind: "import" } | { kind: "aqar" } | { kind: "new"; initial?: Partial<ListingForm> } | { kind: "edit"; listing: Listing };

function licensePill(l: Listing) {
  const d = daysUntil(l.licenseExpiry);
  if (d < 0) return <span className="pill p-bad">منتهٍ · مخفي عن الوكيل</span>;
  if (d <= 7) return <span className="pill p-warn">ينتهي خلال {d} يوم</span>;
  return <span className="pill p-ok">ساري حتى {l.licenseExpiry}</span>;
}

export default function ListingsPage() {
  const [listings, setListings] = useState<Listing[]>([]);
  const [mode, setMode] = useState<Mode>({ kind: "list" });
  const [filter, setFilter] = useState<"all" | "issues" | "nocard">("all");
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      setListings(await api<Listing[]>("/listings"));
    } catch {
      setError("تعذّر تحميل العقارات.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const back = () => {
    setMode({ kind: "list" });
    void load();
  };

  async function setStatus(l: Listing, status: Listing["status"]) {
    await api(`/listings/${l.id}/status`, { method: "POST", json: { status } });
    void load();
  }

  if (mode.kind === "import") return <ImportWizard onDone={back} />;
  if (mode.kind === "aqar") return <AqarLink onDraft={(d) => setMode({ kind: "new", initial: d })} onCancel={back} />;
  if (mode.kind === "new") return <ListingFormView initial={mode.initial} onDone={back} />;
  if (mode.kind === "edit") return <ListingFormView id={mode.listing.id} initial={mode.listing} onDone={back} />;

  const shown = listings.filter((l) =>
    filter === "issues" ? daysUntil(l.licenseExpiry) <= 7 : filter === "nocard" ? !(l.latitude != null) || l.uploadedPhotos + l.photoUrls.length === 0 : true);

  return (
    <>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <div>
          <h1>العقارات</h1>
          <p className="small muted">الوكيل يعرض العقارات النشطة ذات الترخيص الساري فقط، مع رقم الترخيص وتاريخ انتهائه.</p>
        </div>
        <div className="row">
          <button className="btn primary" onClick={() => setMode({ kind: "import" })}>استيراد ملف</button>
          <button className="btn" onClick={() => setMode({ kind: "aqar" })}>من رابط عقار</button>
          <button className="btn" onClick={() => setMode({ kind: "new" })}>إضافة يدوياً</button>
        </div>
      </div>
      {error && <p className="err">{error}</p>}
      <div className="tabs" role="tablist">
        {([["all", `الكل (${listings.length})`], ["issues", "تراخيص تحتاج تحديث"], ["nocard", "بلا موقع أو صور"]] as const).map(([k, label]) => (
          <button key={k} className="tab" role="tab" aria-selected={filter === k} onClick={() => setFilter(k)}>{label}</button>
        ))}
      </div>
      {listings.length === 0 ? (
        <div className="card"><p>لا توجد عقارات بعد. ابدأ باستيراد ملف Excel الذي تستخدمونه، أو الصق رابط إعلان من عقار.</p></div>
      ) : (
        <div className="tbl">
          <table>
            <thead><tr><th>العقار</th><th>الغرض</th><th>السعر (ريال)</th><th>ترخيص الإعلان</th><th>بطاقة العقار</th><th>الحالة</th><th></th></tr></thead>
            <tbody>
              {shown.map((l) => (
                <tr key={l.id} style={{ opacity: l.status === "ACTIVE" ? 1 : 0.6 }}>
                  <td><b>{l.title}</b><div className="small muted">{l.district} · {l.propertyType}{l.areaSqm ? ` · ${fmt(l.areaSqm)} م²` : ""}{l.vacant ? "" : " · مسكون"}</div></td>
                  <td>{l.purpose === "SALE" ? "بيع" : "إيجار"}</td>
                  <td>{fmt(l.price)}{l.purpose === "RENT" && <span className="small muted"> سنوياً</span>}</td>
                  <td><span className="mono">{l.adLicense}</span><br />{licensePill(l)}</td>
                  <td className="small">{l.latitude != null ? "موقع ✓" : "بلا موقع"} · {l.uploadedPhotos + l.photoUrls.length} صور{l.videoUrl ? " · فيديو" : ""}</td>
                  <td>
                    <select aria-label={`حالة ${l.title}`} value={l.status} onChange={(e) => setStatus(l, e.target.value as Listing["status"])} style={{ width: "auto" }}>
                      <option value="ACTIVE">نشط</option><option value="HIDDEN">مخفي</option><option value="SOLD">مباع/مؤجر</option>
                    </select>
                  </td>
                  <td><button className="btn" onClick={() => setMode({ kind: "edit", listing: l })}>تعديل</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function AqarLink({ onDraft, onCancel }: { onDraft: (d: Partial<ListingForm>) => void; onCancel: () => void }) {
  const [url, setUrl] = useState("");
  const [draft, setDraft] = useState<AqarDraft | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [feed, setFeed] = useState(false);

  async function fetchDraft(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      setDraft(await api<AqarDraft>("/imports/aqar-link", { method: "POST", json: { url } }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر قراءة الإعلان.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <h2>إضافة عقار من رابط إعلانكم على عقار</h2>
      <form onSubmit={fetchDraft} className="row">
        <input type="url" dir="ltr" required value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://sa.aqar.fm/…" style={{ flex: 1, minWidth: 240 }} aria-label="رابط الإعلان" />
        <button className="btn primary" disabled={busy}>{busy ? "جارٍ القراءة…" : "اقرأ الإعلان"}</button>
      </form>
      <p className="small muted">نقرأ صفحة الإعلان العامة ونستخرج بياناته، ثم تراجعها قبل الحفظ. قد يتوقف هذا الخيار إذا غيّرت عقار صفحاتها.</p>
      {draft && (
        <div className="card" style={{ background: "var(--bg)" }}>
          <b>{draft.listing.title || "إعلان"}</b>
          <p className="small">{draft.listing.district} · {draft.listing.propertyType} · {fmt(draft.listing.price)} ريال · ترخيص <span className="mono">{draft.listing.adLicense || "—"}</span></p>
          {draft.missing.length > 0 && <p className="small err">لم نجد: {draft.missing.join("، ")}. أكملها في الخطوة التالية.</p>}
          {draft.warnings.map((w) => <p key={w} className="small" style={{ color: "var(--warn)" }}>{w}</p>)}
          <button className="btn primary" onClick={() => onDraft({ ...draft.listing, source: "AQAR_LINK" })}>راجع واحفظ</button>
        </div>
      )}
      {error && <p className="err" role="alert">{error}</p>}
      <div className="card" style={{ background: "var(--bg)" }}>
        <b>مزامنة تلقائية لكل إعلاناتكم في عقار</b>
        <p className="small muted">لا يوجد ربط رسمي متاح حالياً. سجّل اهتمامك ونبلغك عند توفره.</p>
        {feed ? <span className="pill p-ok">طلبك مسجّل</span> :
          <button className="btn" onClick={async () => { await api("/imports/aqar-feed", { method: "POST", json: {} }); setFeed(true); }}>اطلب الربط</button>}
      </div>
      <div><button className="btn" onClick={onCancel}>رجوع</button></div>
    </div>
  );
}
