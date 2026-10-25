"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { ACCESS_LABELS, api, ApiError, riyadhDay, riyadhTime, type Booking } from "@/lib/api";

const STATUS: Record<Booking["status"], [string, string]> = {
  PENDING_APPROVAL: ["بانتظار موافقتك", "p-warn"],
  CONFIRMED: ["مؤكد", "p-ok"],
  REJECTED: ["مرفوض", "p-bad"],
  CANCELLED: ["ملغى", "p-idle"],
  DONE: ["تمت", "p-idle"],
  NO_SHOW: ["لم يحضر", "p-bad"],
};

export default function BookingsPage() {
  const [list, setList] = useState<Booking[]>([]);
  const [review, setReview] = useState<Booking | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      setList(await api<Booking[]>("/bookings"));
    } catch {
      setError("تعذّر تحميل المواعيد.");
    }
  }, []);
  useEffect(() => {
    void load();
    const t = setInterval(() => void load(), 30000);
    return () => clearInterval(t);
  }, [load]);

  const pending = list.filter((b) => b.status === "PENDING_APPROVAL");
  const byDay = new Map<string, Booking[]>();
  list.filter((b) => b.status !== "PENDING_APPROVAL").forEach((b) => {
    const k = riyadhDay(b.startsAt);
    byDay.set(k, [...(byDay.get(k) ?? []), b]);
  });

  async function setStatus(b: Booking, status: string) {
    await api(`/bookings/${b.id}/status`, { method: "POST", json: { status } });
    void load();
  }

  return (
    <>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <div>
          <h1>المواعيد</h1>
          <p className="small muted">يحجز الوكيل ضمن ساعاتك. المعاينة مع المسوّق تُؤكَّد تلقائياً، والزيارة الذاتية تنتظر موافقتك.</p>
        </div>
        <Link className="btn" href="/booking-settings">إعداد المواعيد والرسائل</Link>
      </div>
      {error && <p className="err">{error}</p>}

      {pending.length > 0 && (
        <div className="card" style={{ borderColor: "var(--warn)" }}>
          <h2>طلبات زيارة ذاتية بانتظارك ({pending.length})</h2>
          {pending.map((b) => (
            <div key={b.id} className="row" style={{ justifyContent: "space-between", borderBottom: "1px solid var(--line)", padding: "8px 0" }}>
              <div><b>{b.customerName || b.customerWaId}</b> · {b.listingTitle} — {b.listingDistrict}
                <div className="small muted">{riyadhDay(b.startsAt)} · {riyadhTime(b.startsAt)}</div></div>
              <button className="btn primary" onClick={() => setReview(b)}>راجع الطلب</button>
            </div>
          ))}
        </div>
      )}

      {review && <Review booking={review} onClose={() => { setReview(null); void load(); }} />}

      {byDay.size === 0 && pending.length === 0 && <div className="card"><p className="muted">لا مواعيد في الأسبوعين القادمين.</p></div>}
      {[...byDay.entries()].map(([day, items]) => (
        <div key={day} className="card">
          <h2>{day}</h2>
          {items.map((b) => (
            <div key={b.id} className="row" style={{ justifyContent: "space-between", borderBottom: "1px solid var(--line)", padding: "8px 0" }}>
              <div>
                <b>{riyadhTime(b.startsAt)}</b> · {b.customerName || b.customerWaId} · {b.listingTitle} — {b.listingDistrict}
                <div className="row small" style={{ marginTop: 4 }}>
                  <span className={`pill ${b.mode === "SELF_VISIT" ? "p-warn" : "p-acc"}`}>
                    {b.mode === "SELF_VISIT" ? `زيارة ذاتية${b.accessMethod ? " · " + ACCESS_LABELS[b.accessMethod] : ""}` : `مع ${b.marketerName ?? "مسوّق"}`}
                  </span>
                  <span className={`pill ${STATUS[b.status][1]}`}>{STATUS[b.status][0]}</span>
                  {b.accessCode && <span>الرمز <span className="mono">{b.accessCode}</span></span>}
                </div>
              </div>
              {b.status === "CONFIRMED" && (
                <span className="row">
                  <button className="btn" onClick={() => setStatus(b, "DONE")}>تمت</button>
                  <button className="btn" onClick={() => setStatus(b, "NO_SHOW")}>لم يحضر</button>
                  <button className="btn" onClick={() => setStatus(b, "CANCELLED")}>إلغاء</button>
                </span>
              )}
            </div>
          ))}
        </div>
      ))}
    </>
  );
}

function Review({ booking, onClose }: { booking: Booking; onClose: () => void }) {
  const [method, setMethod] = useState("LOCK");
  const [code, setCode] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function approve() {
    setBusy(true);
    setError("");
    try {
      await api(`/bookings/${booking.id}/approve`, { method: "POST", json: { accessMethod: method, accessCode: code || null } });
      onClose();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّرت الموافقة.");
    } finally {
      setBusy(false);
    }
  }

  async function reject() {
    setBusy(true);
    await api(`/bookings/${booking.id}/reject`, { method: "POST", json: { reason } }).catch(() => undefined);
    onClose();
  }

  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <h2>زيارة ذاتية · {booking.customerName || booking.customerWaId}</h2>
      <p className="small muted">{booking.listingTitle} — {booking.listingDistrict} · {riyadhDay(booking.startsAt)} · {riyadhTime(booking.startsAt)}</p>
      <div className="grid2">
        <label className="f" htmlFor="method">طريقة الدخول
          <select id="method" value={method} onChange={(e) => setMethod(e.target.value)}>
            {Object.entries(ACCESS_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select></label>
        {method === "LOCK" && (
          <label className="f" htmlFor="code">رمز القفل <span className="small muted">اتركه فارغاً لنولّد رمزاً، ثم برمجه في القفل</span>
            <input id="code" type="text" className="mono" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} /></label>
        )}
      </div>
      <p className="small">بعد الموافقة يصل العميل على واتساب: الموقع، والموعد، وطريقة الدخول بصيغة رسالتك في إعداد المواعيد.</p>
      <div className="row">
        <button className="btn primary" disabled={busy} onClick={approve}>وافق وأرسل للعميل</button>
        <input type="text" aria-label="سبب الاعتذار" placeholder="سبب الاعتذار (اختياري)" value={reason} onChange={(e) => setReason(e.target.value)} style={{ maxWidth: 260 }} />
        <button className="btn" disabled={busy} onClick={reject}>اعتذر واقترح معاينة مع مسوّق</button>
        <button className="btn" onClick={onClose}>إغلاق</button>
      </div>
      {error && <p className="err" role="alert">{error}</p>}
    </div>
  );
}
