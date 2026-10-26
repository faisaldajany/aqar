"use client";

import Link from "next/link";
import { SaduMark } from "@/components/Sadu";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { api, ApiError } from "@/lib/api";

export default function SignupPage() {
  const router = useRouter();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setError("");
    try {
      await api("/auth/signup", {
        method: "POST",
        json: {
          officeName: f.get("officeName"),
          falLicense: f.get("falLicense"),
          fullName: f.get("fullName"),
          email: f.get("email"),
          password: f.get("password"),
        },
      });
      router.replace("/whatsapp");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر إنشاء الحساب.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth">
      <Link href="/" className="brand authbrand"><SaduMark size={30} />حداء</Link>
      <form className="card" onSubmit={submit}>
        <h1>سجّل مكتبك</h1>
        <p className="small muted">بعد التسجيل تربط رقم واتساب المكتب مباشرة، دون زيارة.</p>
        <label className="f" htmlFor="officeName">اسم المكتب
          <input id="officeName" name="officeName" type="text" required maxLength={120} />
        </label>
        <label className="f" htmlFor="falLicense">رقم رخصة فال
          <input id="falLicense" name="falLicense" type="text" inputMode="numeric" required pattern="[0-9\s-]{8,16}" />
        </label>
        <label className="f" htmlFor="fullName">اسمك
          <input id="fullName" name="fullName" type="text" required maxLength={120} />
        </label>
        <label className="f" htmlFor="email">البريد الإلكتروني
          <input id="email" name="email" type="email" autoComplete="email" required />
        </label>
        <label className="f" htmlFor="password">كلمة المرور <span className="small muted">8 أحرف على الأقل</span>
          <input id="password" name="password" type="password" autoComplete="new-password" required minLength={8} />
        </label>
        {error && <p className="err" role="alert">{error}</p>}
        <button className="btn primary" disabled={busy}>{busy ? "جارٍ الإنشاء…" : "أنشئ الحساب"}</button>
        <p className="small muted">لديك حساب؟ <Link href="/login">سجّل الدخول</Link></p>
      </form>
    </main>
  );
}
