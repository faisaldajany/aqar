"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { Logo } from "@/components/Logo";
import { api, ApiError } from "@/lib/api";

export default function ForgotPage() {
  const [sent, setSent] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const email = String(new FormData(e.currentTarget).get("email") ?? "").trim();
    setBusy(true);
    setError("");
    try {
      const r = await api<{ message: string }>("/auth/forgot", { method: "POST", json: { email } });
      setSent(r.message);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر الإرسال. حاول مرة أخرى.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth">
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      {sent ? (
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>تحقق من بريدك</h1>
          <p>{sent}</p>
          <p className="small muted">لم يصلك شيء؟ تحقق من الرسائل غير المرغوبة، أو <Link href="/contact">تواصل معنا</Link>.</p>
          <Link className="btn" href="/login">رجوع لتسجيل الدخول</Link>
        </div>
      ) : (
        <form className="card" onSubmit={submit}>
          <h1>نسيت كلمة المرور</h1>
          <p className="small muted">اكتب بريدك المسجّل وسنرسل لك رابطاً لتعيين كلمة مرور جديدة.</p>
          <label className="f" htmlFor="email">البريد الإلكتروني
            <input id="email" name="email" type="email" autoComplete="email" required />
          </label>
          {error && <p className="err" role="alert">{error}</p>}
          <button className="btn primary" disabled={busy}>{busy ? "جارٍ الإرسال…" : "أرسل الرابط"}</button>
          <p className="small muted"><Link href="/login">رجوع لتسجيل الدخول</Link></p>
        </form>
      )}
    </main>
  );
}
