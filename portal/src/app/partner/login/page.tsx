"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { Logo } from "@/components/Logo";
import { api, ApiError } from "@/lib/api";

export default function PartnerLogin() {
  const router = useRouter();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setError("");
    try {
      await api("/partner/login", { method: "POST", json: { email: f.get("email"), password: f.get("password") } });
      router.replace("/partner");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر تسجيل الدخول.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth">
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <form className="card" onSubmit={submit}>
        <h1>بوابة المسوّقين</h1>
        <p className="small muted">لمسوّقي حداء بالعمولة. أصحاب المكاتب يدخلون من <Link href="/login">هنا</Link>.</p>
        <label className="f" htmlFor="email">البريد الإلكتروني
          <input id="email" name="email" type="email" autoComplete="email" required />
        </label>
        <label className="f" htmlFor="password"><span className="lbl">كلمة المرور <Link href="/partner/forgot" className="small">نسيت كلمة المرور؟</Link></span>
          <input id="password" name="password" type="password" autoComplete="current-password" required />
        </label>
        {error && <p className="err" role="alert">{error}</p>}
        <button className="btn primary" disabled={busy}>{busy ? "جارٍ الدخول…" : "دخول"}</button>
      </form>
    </main>
  );
}
