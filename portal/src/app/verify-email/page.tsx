"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { Logo } from "@/components/Logo";
import { api, ApiError, type Me } from "@/lib/api";

/** After signup: type the 6-digit code from the email (or click its link) before using the portal. */
export default function VerifyEmailPage() {
  const router = useRouter();
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
    const t = setTimeout(() => setWait((w) => w - 1), 1000);
    return () => clearTimeout(t);
  }, [wait]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await api("/verify-email", { method: "POST", json: { code } });
      router.replace("/dashboard");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر التحقق. حاول مرة أخرى.");
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
      setNote("أرسلنا رسالة جديدة فيها رمز جديد. الرمز السابق لم يعد يعمل.");
      setCode("");
      setWait(60);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر الإرسال. حاول بعد قليل.");
    }
  }

  async function logout() {
    await api("/auth/logout", { method: "POST" }).catch(() => undefined);
    router.replace("/login");
  }

  return (
    <main className="auth">
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <form className="card" onSubmit={submit}>
        <h1>أكّد بريدك</h1>
        <p className="small">
          أرسلنا رمزاً من 6 أرقام إلى <b dir="ltr">{email || "…"}</b>. اكتبه هنا، أو اضغط زر «تأكيد البريد» في الرسالة.
        </p>
        <label className="f" htmlFor="code">رمز التحقق
          <input id="code" name="code" inputMode="numeric" autoComplete="one-time-code" dir="ltr" maxLength={7} required
                 value={code} onChange={(e) => setCode(e.target.value.replace(/[^0-9٠-٩]/g, "").slice(0, 6))}
                 style={{ fontSize: 26, letterSpacing: 8, textAlign: "center", fontFamily: "ui-monospace, Menlo, monospace" }}
                 placeholder="••••••" aria-describedby="code-help" />
        </label>
        <span id="code-help" className="small muted">الرمز صالح لمدة 30 دقيقة. لم تصلك الرسالة؟ راجع مجلد الرسائل غير المرغوبة.</span>
        {error && <p className="err" role="alert">{error}</p>}
        {note && <p className="small" role="status">{note}</p>}
        <button className="btn primary" disabled={busy || code.length !== 6}>{busy ? "جارٍ التحقق…" : "تأكيد"}</button>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <button type="button" className="linkbtn small" disabled={wait > 0} onClick={() => void resend()}>
            {wait > 0 ? `أرسل الرمز مرة أخرى بعد ${wait} ث` : "أرسل الرمز مرة أخرى"}
          </button>
          <button type="button" className="linkbtn small" onClick={() => void logout()}>تسجيل الخروج</button>
        </div>
        <p className="small muted" style={{ margin: 0 }}>البريد خطأ؟ سجّل الخروج وأنشئ الحساب من جديد، أو راسلنا على support@hidaaco.com.</p>
      </form>
    </main>
  );
}
