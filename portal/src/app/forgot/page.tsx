"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { Logo } from "@/components/Logo";
import { LangToggle, useLang } from "@/components/LangProvider";
import { api, ApiError } from "@/lib/api";

export default function ForgotPage() {
  const { t, en } = useLang();
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
      setSent(en ? "If this email belongs to an account, we've sent it a link to set a new password." : r.message);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t("تعذّر الإرسال. حاول مرة أخرى.", "Couldn't send. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      {sent ? (
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>{t("تحقق من بريدك", "Check your email")}</h1>
          <p>{sent}</p>
          <p className="small muted">
            {t(<>لم يصلك شيء؟ تحقق من الرسائل غير المرغوبة، أو <Link href="/contact">تواصل معنا</Link>.</>,
               <>Nothing arrived? Check your spam folder, or <Link href="/contact">contact us</Link>.</>)}
          </p>
          <Link className="btn" href="/login">{t("رجوع لتسجيل الدخول", "Back to sign in")}</Link>
        </div>
      ) : (
        <form className="card" onSubmit={submit}>
          <h1>{t("نسيت كلمة المرور", "Forgot your password")}</h1>
          <p className="small muted">{t("اكتب بريدك المسجّل وسنرسل لك رابطاً لتعيين كلمة مرور جديدة.", "Enter the email you registered with and we'll send you a link to set a new password.")}</p>
          <label className="f" htmlFor="email">{t("البريد الإلكتروني", "Email")}
            <input id="email" name="email" type="email" autoComplete="email" required />
          </label>
          {error && <p className="err" role="alert">{error}</p>}
          <button className="btn primary" disabled={busy}>{busy ? t("جارٍ الإرسال…", "Sending…") : t("أرسل الرابط", "Send the link")}</button>
          <p className="small muted"><Link href="/login">{t("رجوع لتسجيل الدخول", "Back to sign in")}</Link></p>
        </form>
      )}
    </main>
  );
}
