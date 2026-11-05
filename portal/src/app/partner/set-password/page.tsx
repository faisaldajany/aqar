"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { FormEvent, Suspense, useState } from "react";
import { Logo } from "@/components/Logo";
import { api, ApiError } from "@/lib/api";

export default function PartnerSetPassword() {
  return <Suspense fallback={null}><SetPasswordForm /></Suspense>;
}

/** From the invitation (or a forgotten-password email): set the password, then straight to the marketer's page. */
function SetPasswordForm() {
  const router = useRouter();
  const token = useSearchParams().get("token") ?? "";
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
      await api("/partner/password", { method: "POST", json: { token, password } });
      router.replace("/partner");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر الحفظ. حاول مرة أخرى.");
      setBusy(false);
    }
  }

  return (
    <main className="auth">
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      {!token ? (
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>الرابط ناقص</h1>
          <p>افتح الرابط كاملاً من رسالة البريد، أو اطلب رابطاً جديداً.</p>
          <Link className="btn" href="/partner/forgot">اطلب رابطاً جديداً</Link>
        </div>
      ) : (
        <form className="card" onSubmit={submit}>
          <h1>كلمة مرور بوابة المسوّقين</h1>
          <label className="f" htmlFor="password">كلمة المرور <span className="small muted">8 أحرف على الأقل</span>
            <input id="password" name="password" type="password" autoComplete="new-password" required minLength={8} maxLength={128} />
          </label>
          <label className="f" htmlFor="confirm">أعد كتابتها
            <input id="confirm" name="confirm" type="password" autoComplete="new-password" required minLength={8} maxLength={128} />
          </label>
          {error && <p className="err" role="alert">{error}</p>}
          <button className="btn primary" disabled={busy}>{busy ? "جارٍ الحفظ…" : "احفظ وادخل"}</button>
        </form>
      )}
    </main>
  );
}
