"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { Logo } from "@/components/Logo";
import { LangToggle, useLang } from "@/components/LangProvider";
import { api, ApiError, type Me } from "@/lib/api";

/** After signup: type the 6-digit code from the email (or click its link) before using the portal. */
export default function VerifyEmailPage() {
  const router = useRouter();
  const { t } = useLang();
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [wait, setWait] = useState(60);

  useEffect(() => {
    api<Me>("/me").then((m) => {
      if (m.user.emailVerified !== false) router.replace("/dashboard");
      else setEmail(m.user.email);
    }).catch((e) => {
      if (e instanceof ApiError && e.status === 401) router.replace("/login");
    });
  }, [router]);

  useEffect(() => {
    if (wait <= 0) return;
    const id = setTimeout(() => setWait((w) => w - 1), 1000);
    return () => clearTimeout(id);
  }, [wait]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await api("/verify-email", { method: "POST", json: { code } });
      router.replace("/dashboard");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t("تعذّر التحقق. حاول مرة أخرى.", "Couldn't verify. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  async function resend() {
    setError("");
    setNote("");
    try {
      const r = await api<{ sent: boolean; verified: boolean }>("/verify-email/resend", { method: "POST" });
      if (r.verified) { router.replace("/dashboard"); return; }
      setNote(t("أرسلنا رسالة جديدة فيها رمز جديد. الرمز السابق لم يعد يعمل.", "We've sent a new email with a new code. The previous code no longer works."));
      setCode("");
      setWait(60);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t("تعذّر الإرسال. حاول بعد قليل.", "Couldn't send. Try again in a moment."));
    }
  }

  async function logout() {
    await api("/auth/logout", { method: "POST" }).catch(() => undefined);
    router.replace("/login");
  }

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <form className="card" onSubmit={submit}>
        <h1>{t("أكّد بريدك", "Confirm your email")}</h1>
        <p className="small">
          {t(<>أرسلنا رمزاً من 6 أرقام إلى <b dir="ltr">{email || "…"}</b>. اكتبه هنا، أو اضغط زر «تأكيد البريد» في الرسالة.</>,
             <>We sent a 6-digit code to <b>{email || "…"}</b>. Type it here, or press the button in the email.</>)}
        </p>
        <label className="f" htmlFor="code">{t("رمز التحقق", "Verification code")}
          <input id="code" name="code" inputMode="numeric" autoComplete="one-time-code" dir="ltr" maxLength={7} required
                 value={code} onChange={(e) => setCode(e.target.value.replace(/[^0-9٠-٩]/g, "").slice(0, 6))}
                 style={{ fontSize: 26, letterSpacing: 8, textAlign: "center", fontFamily: "ui-monospace, Menlo, monospace" }}
                 placeholder="••••••" aria-describedby="code-help" />
        </label>
        <span id="code-help" className="small muted">
          {t("الرمز صالح لمدة 30 دقيقة. لم تصلك الرسالة؟ راجع مجلد الرسائل غير المرغوبة.", "The code is valid for 30 minutes. No email? Check your spam folder.")}
        </span>
        {error && <p className="err" role="alert">{error}</p>}
        {note && <p className="small" role="status">{note}</p>}
        <button className="btn primary" disabled={busy || code.length !== 6}>{busy ? t("جارٍ التحقق…", "Verifying…") : t("تأكيد", "Confirm")}</button>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <button type="button" className="linkbtn small" disabled={wait > 0} onClick={() => void resend()}>
            {wait > 0 ? t(`أرسل الرمز مرة أخرى بعد ${wait} ث`, `Send the code again in ${wait}s`) : t("أرسل الرمز مرة أخرى", "Send the code again")}
          </button>
          <button type="button" className="linkbtn small" onClick={() => void logout()}>{t("تسجيل الخروج", "Sign out")}</button>
        </div>
        <p className="small muted" style={{ margin: 0 }}>
          {t("البريد خطأ؟ سجّل الخروج وأنشئ الحساب من جديد، أو راسلنا على support@hidaaco.com.", "Wrong email? Sign out and create the account again, or write to support@hidaaco.com.")}
        </p>
      </form>
    </main>
  );
}
