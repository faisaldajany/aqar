"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { api, ApiError } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
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
      setError(err instanceof ApiError ? err.message : "تعذّر تسجيل الدخول.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth">
      <form className="card" onSubmit={submit}>
        <h1>تسجيل الدخول</h1>
        <label className="f" htmlFor="email">البريد الإلكتروني
          <input id="email" name="email" type="email" autoComplete="email" required />
        </label>
        <label className="f" htmlFor="password">كلمة المرور
          <input id="password" name="password" type="password" autoComplete="current-password" required />
        </label>
        {error && <p className="err" role="alert">{error}</p>}
        <button className="btn primary" disabled={busy}>{busy ? "جارٍ الدخول…" : "دخول"}</button>
        <p className="small muted">مكتب جديد؟ <Link href="/signup">سجّل مكتبك</Link></p>
      </form>
    </main>
  );
}
