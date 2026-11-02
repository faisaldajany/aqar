"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { Logo } from "@/components/Logo";
import { api, ApiError } from "@/lib/api";

/** Operator sign-in with the admin key from Secret Manager. */
export default function OpsLogin() {
  const router = useRouter();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const key = String(new FormData(e.currentTarget).get("key") ?? "").trim();
    setBusy(true);
    setError("");
    try {
      await api("/ops/login", { method: "POST", json: { key } });
      router.replace("/ops");
    } catch (err) {
      setError(err instanceof ApiError
        ? err.status === 404 ? "لوحة الإدارة غير مفعّلة على هذا الخادم (ADMIN_TOKEN)." : err.message
        : "تعذّر الاتصال بالخادم.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth">
      <span className="brand authbrand"><Logo size={30} /></span>
      <form className="card" onSubmit={submit}>
        <h1>إدارة حداء</h1>
        <p className="small muted">للفريق فقط. المفتاح في Secret Manager باسم admin-token.</p>
        <label className="f" htmlFor="key">مفتاح الإدارة
          <input id="key" name="key" type="password" dir="ltr" autoComplete="current-password" required minLength={16} />
        </label>
        {error && <p className="err" role="alert">{error}</p>}
        <button className="btn primary" disabled={busy}>{busy ? "جارٍ التحقق…" : "دخول"}</button>
        <p className="small muted" dir="ltr" style={{ textAlign: "left" }}>gcloud secrets versions access latest --secret=admin-token</p>
      </form>
    </main>
  );
}
