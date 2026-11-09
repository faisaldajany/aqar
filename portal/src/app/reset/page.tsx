"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { FormEvent, Suspense, useState } from "react";
import { Logo } from "@/components/Logo";
import { LangToggle, useLang } from "@/components/LangProvider";
import { api, ApiError } from "@/lib/api";

export default function ResetPage() {
  return <Suspense fallback={null}><ResetForm /></Suspense>;
}

function ResetForm() {
  const { t } = useLang();
  const token = useSearchParams().get("token") ?? "";
  const [done, setDone] = useState(false);
  const [error, setError] = useState("");
  const [expired, setExpired] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const password = String(f.get("password") ?? "");
    if (password !== String(f.get("confirm") ?? "")) {
      setError(t("كلمتا المرور غير متطابقتين.", "The two passwords don't match."));
      return;
    }
    setBusy(true);
    setError("");
    setExpired(false);
    try {
      await api("/auth/reset", { method: "POST", json: { token, password } });
      setDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t("تعذّر الحفظ. حاول مرة أخرى.", "Couldn't save. Please try again."));
      setExpired(err instanceof ApiError && err.code === "reset_invalid");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      {done ? (
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>{t("تم تغيير كلمة المرور", "Password changed")}</h1>
          <p>{t("سجّل الدخول بكلمة المرور الجديدة. أي جلسة مفتوحة على جهاز آخر انتهت.", "Sign in with your new password. Any session open on another device has ended.")}</p>
          <Link className="btn primary" href="/login">{t("تسجيل الدخول", "Sign in")}</Link>
        </div>
      ) : !token ? (
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>{t("الرابط ناقص", "The link is incomplete")}</h1>
          <p>{t("افتح الرابط كاملاً من رسالة البريد، أو اطلب رابطاً جديداً.", "Open the full link from the email, or ask for a new one.")}</p>
          <Link className="btn" href="/forgot">{t("اطلب رابطاً جديداً", "Ask for a new link")}</Link>
        </div>
      ) : (
        <form className="card" onSubmit={submit}>
          <h1>{t("كلمة مرور جديدة", "New password")}</h1>
          <label className="f" htmlFor="password">{t("كلمة المرور الجديدة", "New password")} <span className="small muted">{t("8 أحرف على الأقل", "At least 8 characters")}</span>
            <input id="password" name="password" type="password" autoComplete="new-password" required minLength={8} maxLength={128} />
          </label>
          <label className="f" htmlFor="confirm">{t("أعد كتابتها", "Type it again")}
            <input id="confirm" name="confirm" type="password" autoComplete="new-password" required minLength={8} maxLength={128} />
          </label>
          {error && <p className="err" role="alert">{error}{expired && <> <Link href="/forgot">{t("اطلب رابطاً جديداً", "Ask for a new link")}</Link></>}</p>}
          <button className="btn primary" disabled={busy}>{busy ? t("جارٍ الحفظ…", "Saving…") : t("احفظ كلمة المرور", "Save password")}</button>
        </form>
      )}
    </main>
  );
}
