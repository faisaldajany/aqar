"use client";

import { CSSProperties, FormEvent, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { api, ApiError } from "@/lib/api";

// The home page is always light, so the fields use fixed colors rather than the portal's theme variables.
const field: CSSProperties = {
  width: "100%", minHeight: 44, padding: "9px 12px", border: "1px solid #E2DFD8", borderRadius: 10,
  background: "#FFFFFF", color: "#121722", font: "inherit", fontSize: 15, boxSizing: "border-box",
};
const label: CSSProperties = { display: "flex", flexDirection: "column", gap: 4, fontSize: 14, fontWeight: 600, color: "#121722" };

/** Short support form inside the "talk to the owner" card on the home page; forwarded by email by the backend. */
export function SupportCardForm() {
  const [mount, setMount] = useState<HTMLElement | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [ref, setRef] = useState("");

  useEffect(() => {
    setMount(document.querySelector<HTMLElement>("#home [data-support-form]"));
  }, []);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const contact = String(f.get("contact") ?? "").trim();
    const isEmail = contact.includes("@");
    setBusy(true);
    setError("");
    try {
      const r = await api<{ reference?: string }>("/public/support", {
        method: "POST",
        json: {
          name: f.get("name"), office: f.get("office"), message: f.get("message"), website: f.get("website"),
          email: isEmail ? contact : null, phone: isEmail ? null : contact,
          topic: "سؤال عن الخدمة", source: "website",
        },
      });
      setRef(r.reference ?? "تم");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر الإرسال. حاول مرة ثانية أو راسلنا على البريد.");
    } finally {
      setBusy(false);
    }
  }

  if (!mount) return null;

  const body = ref ? (
    <div role="status" style={{ marginTop: 16, padding: 16, borderRadius: 12, background: "#FFFFFF", fontSize: 15, color: "#121722" }}>
      <strong style={{ display: "block", marginBottom: 4 }}>وصلتني رسالتك</strong>
      رقم الطلب <span dir="ltr" style={{ fontFamily: "ui-monospace, monospace" }}>{ref}</span>. أرد عليك خلال يوم عمل.
    </div>
  ) : (
    <form onSubmit={submit} style={{ marginTop: 16, display: "flex", flexDirection: "column", gap: 10 }}>
      <label style={label}>الاسم<input style={field} name="name" required maxLength={120} autoComplete="name" /></label>
      <label style={label}>اسم المكتب <span style={{ fontWeight: 400, color: "#4A5160" }}>(اختياري)</span>
        <input style={field} name="office" maxLength={200} autoComplete="organization" /></label>
      <label style={label}>جوالك أو بريدك
        <input style={{ ...field, direction: "ltr", textAlign: "right" }} name="contact" required maxLength={200} placeholder="05xxxxxxxx" /></label>
      <label style={label}>رسالتك
        <textarea style={{ ...field, minHeight: 96, resize: "vertical" }} name="message" required minLength={10} maxLength={4000} /></label>
      {/* Hidden from people; bots fill it in. */}
      <input type="text" name="website" tabIndex={-1} autoComplete="off" aria-hidden="true"
        style={{ position: "absolute", left: "-9999px", width: 1, height: 1 }} />
      {error && <p role="alert" style={{ margin: 0, fontSize: 14, color: "#A3192D" }}>{error}</p>}
      <button type="submit" disabled={busy} style={{
        minHeight: 44, border: 0, borderRadius: 10, background: "#A3192D", color: "#FFFFFF", fontWeight: 600,
        fontSize: 15, cursor: busy ? "wait" : "pointer", opacity: busy ? 0.7 : 1,
      }}>{busy ? "جارٍ الإرسال…" : "أرسل"}</button>
    </form>
  );
  return createPortal(body, mount);
}
