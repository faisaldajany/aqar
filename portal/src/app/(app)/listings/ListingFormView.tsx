"use client";

import { FormEvent, useEffect, useState } from "react";
import { ACCESS_LABELS, api, ApiError, parseLocation, type ListingForm, type Marketer } from "@/lib/api";

const EMPTY: ListingForm = {
  title: "", district: "", propertyType: "", purpose: "SALE", price: 0, areaSqm: null, rooms: null,
  adLicense: "", licenseExpiry: "", vacant: true, marketerId: null, latitude: null, longitude: null,
  videoUrl: null, photoUrls: [], description: null, sourceUrl: null,
};

/** Create or edit one listing. Photos can be uploaded once the listing exists. */
export default function ListingFormView({ id, initial, onDone }: { id?: string; initial?: Partial<ListingForm>; onDone: () => void }) {
  const [f, setF] = useState<ListingForm>({ ...EMPTY, ...initial });
  const [loc, setLoc] = useState(initial?.latitude != null ? `${initial.latitude}, ${initial.longitude}` : "");
  const [marketers, setMarketers] = useState<Marketer[]>([]);
  const [photos, setPhotos] = useState<{ id: string; url: string }[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [access, setAccess] = useState({
    methods: (initial?.selfVisitAccess ?? []) as string[],
    lockNote: initial?.accessLockNote ?? "", guard: initial?.accessGuard ?? "", keyPoint: initial?.accessKeyPoint ?? "",
  });

  useEffect(() => {
    api<Marketer[]>("/marketers").then(setMarketers).catch(() => undefined);
    if (id) api<{ id: string; url: string }[]>(`/listings/${id}/photos`).then(setPhotos).catch(() => undefined);
  }, [id]);

  const set = <K extends keyof ListingForm>(k: K, v: ListingForm[K]) => setF((x) => ({ ...x, [k]: v }));

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError("");
    const p = loc.trim() ? parseLocation(loc) : null;
    if (loc.trim() && !p) {
      setError("الموقع: الصق رابط قوقل ماب أو إحداثيات مثل 24.85, 46.64");
      return;
    }
    setBusy(true);
    try {
      const body = { ...f, latitude: p?.lat ?? null, longitude: p?.lng ?? null, videoUrl: f.videoUrl || null };
      if (id) {
        await api(`/listings/${id}`, { method: "PUT", json: body });
        await api(`/listings/${id}/access`, { method: "PUT", json: access });
      }
      else await api("/listings", { method: "POST", json: body });
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر الحفظ.");
    } finally {
      setBusy(false);
    }
  }

  async function upload(file: File) {
    if (!id) return;
    const form = new FormData();
    form.append("file", file);
    try {
      await api(`/listings/${id}/photos`, { method: "POST", body: form });
      setPhotos(await api(`/listings/${id}/photos`));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر رفع الصورة.");
    }
  }

  async function removePhoto(pid: string) {
    await api(`/listings/${id}/photos/${pid}`, { method: "DELETE" });
    setPhotos((x) => x.filter((p) => p.id !== pid));
  }

  return (
    <form className="card" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <h2>{id ? "تعديل العقار" : "عقار جديد"}</h2>
      <div className="grid3">
        <label className="f" htmlFor="title">الوصف المختصر<input id="title" type="text" required value={f.title} onChange={(e) => set("title", e.target.value)} /></label>
        <label className="f" htmlFor="district">الحي<input id="district" type="text" required value={f.district} onChange={(e) => set("district", e.target.value)} /></label>
        <label className="f" htmlFor="ptype">نوع العقار<input id="ptype" type="text" required value={f.propertyType} onChange={(e) => set("propertyType", e.target.value)} placeholder="فيلا، شقة، أرض…" /></label>
        <label className="f" htmlFor="purpose">الغرض
          <select id="purpose" value={f.purpose} onChange={(e) => set("purpose", e.target.value as "SALE" | "RENT")}><option value="SALE">بيع</option><option value="RENT">إيجار</option></select></label>
        <label className="f" htmlFor="price">السعر (ريال{f.purpose === "RENT" ? " سنوياً" : ""})<input id="price" type="number" min={1} required value={f.price || ""} onChange={(e) => set("price", Number(e.target.value))} /></label>
        <label className="f" htmlFor="area">المساحة (م²)<input id="area" type="number" min={1} value={f.areaSqm ?? ""} onChange={(e) => set("areaSqm", e.target.value ? Number(e.target.value) : null)} /></label>
        <label className="f" htmlFor="rooms">الغرف<input id="rooms" type="number" min={0} value={f.rooms ?? ""} onChange={(e) => set("rooms", e.target.value ? Number(e.target.value) : null)} /></label>
        <label className="f" htmlFor="license">رقم ترخيص الإعلان<input id="license" type="text" className="mono" required pattern="7\d{9}" value={f.adLicense} disabled={!!id} onChange={(e) => set("adLicense", e.target.value.replace(/\D/g, ""))} /></label>
        <label className="f" htmlFor="expiry">تاريخ انتهاء الترخيص (ميلادي)<input id="expiry" type="date" required value={f.licenseExpiry} onChange={(e) => set("licenseExpiry", e.target.value)} /></label>
        <label className="f" htmlFor="marketer">المسوّق المسؤول
          <select id="marketer" value={f.marketerId ?? ""} onChange={(e) => set("marketerId", e.target.value || null)}>
            <option value="">حسب الحي</option>
            {marketers.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
          </select></label>
        <label className="f" htmlFor="vacant">الحالة
          <select id="vacant" value={f.vacant ? "1" : "0"} onChange={(e) => set("vacant", e.target.value === "1")}><option value="1">شاغر</option><option value="0">مسكون</option></select></label>
      </div>
      <div className="grid2">
        <label className="f" htmlFor="loc">الموقع <span className="small muted">رابط قوقل ماب أو إحداثيات</span>
          <input id="loc" type="text" dir="ltr" value={loc} onChange={(e) => setLoc(e.target.value)} placeholder="https://maps.google.com/…" /></label>
        <label className="f" htmlFor="video">رابط فيديو أو جولة افتراضية<input id="video" type="url" dir="ltr" value={f.videoUrl ?? ""} onChange={(e) => set("videoUrl", e.target.value)} /></label>
      </div>
      <label className="f" htmlFor="desc">وصف إضافي يستفيد منه الوكيل<textarea id="desc" rows={3} value={f.description ?? ""} onChange={(e) => set("description", e.target.value)} /></label>
      {id && (
        <div>
          <span className="f">الزيارة الذاتية: كيف يدخل العميل دون مسوّق؟ <span className="small muted">تُرسل للعميل بعد تأكيد الموعد فقط</span></span>
          <div className="grid3" style={{ marginTop: 6 }}>
            {(["LOCK", "GUARD", "KEY"] as const).map((m) => (
              <div key={m}>
                <label className="check"><input type="checkbox" checked={access.methods.includes(m)}
                  onChange={(e) => setAccess({ ...access, methods: e.target.checked ? [...access.methods, m] : access.methods.filter((x) => x !== m) })} />{ACCESS_LABELS[m]}</label>
                <input type="text" aria-label={`تفاصيل ${ACCESS_LABELS[m]}`}
                  placeholder={m === "LOCK" ? "مثل: قفل على الباب الرئيسي" : m === "GUARD" ? "اسم الحارس أو المالك ورقمه" : "مكان استلام المفتاح"}
                  value={m === "LOCK" ? access.lockNote : m === "GUARD" ? access.guard : access.keyPoint}
                  onChange={(e) => setAccess({ ...access, ...(m === "LOCK" ? { lockNote: e.target.value } : m === "GUARD" ? { guard: e.target.value } : { keyPoint: e.target.value }) })} />
              </div>
            ))}
          </div>
        </div>
      )}
      {id ? (
        <div>
          <span className="f">الصور ({photos.length}/10) <span className="small muted">يرسل الوكيل أول 3 صور مع الموقع</span></span>
          <div className="photos" style={{ marginTop: 6 }}>
            {photos.map((p) => (
              <span key={p.id} style={{ position: "relative" }}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={`/api${p.url.replace(/^\/api/, "")}`} alt="" />
                <button type="button" className="btn" style={{ position: "absolute", top: 2, left: 2, padding: "0 6px" }} onClick={() => removePhoto(p.id)} aria-label="حذف الصورة">×</button>
              </span>
            ))}
          </div>
          {photos.length < 10 && <input type="file" accept="image/jpeg,image/png,image/webp" onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} style={{ marginTop: 8 }} aria-label="رفع صورة" />}
        </div>
      ) : (
        <p className="small muted">بعد الحفظ تستطيع رفع صور العقار من زر التعديل.</p>
      )}
      {error && <p className="err" role="alert">{error}</p>}
      <div className="row">
        <button className="btn primary" disabled={busy}>{busy ? "جارٍ الحفظ…" : "حفظ"}</button>
        <button type="button" className="btn" onClick={onDone}>إلغاء</button>
      </div>
    </form>
  );
}
