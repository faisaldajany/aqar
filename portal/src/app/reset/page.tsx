"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { FormEvent, Suspense, useState } from "react";
import { Logo } from "@/components/Logo";
import { api, ApiError } from "@/lib/api";

export default function ResetPage() {
  return <Suspense fallback={null}><ResetForm /></Suspense>;
}

function ResetForm() {
  const token = useSearchParams().get("token") ?? "";
  const [done, setDone] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const password = String(f.get("password") ?? "");
    if (password !== String(f.get("confirm") ?? "")) {
      setError("كلمتا المرور غير متطابقتين.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      await api("/auth/reset", { method: "POST", json: { token, password } });
      setDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر الحفظ. حاول مرة أخرى.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth">
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      {done ? (
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>تم تغيير كلمة المرور</h1>
          <p>سجّل الدخول بكلمة المرور الجديدة. أي جلسة مفتوحة على جهاز آخر انتهت.</p>
          <Link className="btn primary" href="/login">تسجيل الدخول</Link>
        </div>
      ) : !token ? (
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>الرابط ناقص</h1>
          <p>افتح الرابط كاملاً من رسالة البريد، أو اطلب رابطاً جديداً.</p>
          <Link className="btn" href="/forgot">اطلب رابطاً جديداً</Link>
        </div>
      ) : (
        <form className="card" onSubmit={submit}>
          <h1>كلمة مرور جديدة</h1>
          <label className="f" htmlFor="password">كلمة المرور الجديدة <span className="small muted">8 أحرف على الأقل</span>
            <input id="password" name="password" type="password" autoComplete="new-password" required minLength={8} maxLength={128} />
          </label>
          <label className="f" htmlFor="confirm">أعد كتابتها
            <input id="confirm" name="confirm" type="password" autoComplete="new-password" required minLength={8} maxLength={128} />
          </label>
          {error && <p className="err" role="alert">{error}{error.includes("انتهت") && <> <Link href="/forgot">اطلب رابطاً جديداً</Link></>}</p>}
          <button className="btn primary" disabled={busy}>{busy ? "جارٍ الحفظ…" : "احفظ كلمة المرور"}</button>
        </form>
      )}
    </main>
  );
}
