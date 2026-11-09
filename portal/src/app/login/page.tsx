"use client";

import Link from "next/link";
import { Logo } from "@/components/Logo";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { LangToggle, useLang } from "@/components/LangProvider";
import { api, ApiError } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const { t } = useLang();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setBusy(true);
    setError("");
    try {
      await api("/auth/login", { method: "POST", json: { email: form.get("email"), password: form.get("password") } });
      router.replace("/dashboard");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t("تعذّر تسجيل الدخول.", "Couldn't sign in."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <form className="card" onSubmit={submit}>
        <h1>{t("تسجيل الدخول", "Sign in")}</h1>
        <label className="f" htmlFor="email">{t("البريد الإلكتروني", "Email")}
          <input id="email" name="email" type="email" autoComplete="email" required />
        </label>
        <label className="f" htmlFor="password">
          <span className="lbl">{t("كلمة المرور", "Password")} <Link href="/forgot" className="small">{t("نسيت كلمة المرور؟", "Forgot your password?")}</Link></span>
          <input id="password" name="password" type="password" autoComplete="current-password" required />
        </label>
        {error && <p className="err" role="alert">{error}</p>}
        <button className="btn primary" disabled={busy}>{busy ? t("جارٍ الدخول…", "Signing in…") : t("دخول", "Sign in")}</button>
        <p className="small muted">
          {t("مكتب جديد؟", "New office?")} <Link href="/signup">{t("سجّل مكتبك", "Register your office")}</Link> · <Link href="/contact">{t("تواصل معنا", "Contact us")}</Link>
        </p>
      </form>
    </main>
  );
}
