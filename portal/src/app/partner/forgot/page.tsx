"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { Logo } from "@/components/Logo";
import { api } from "@/lib/api";

export default function PartnerForgot() {
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    await api("/partner/forgot", { method: "POST", json: { email: new FormData(e.currentTarget).get("email") } }).catch(() => undefined);
    setSent(true);
    setBusy(false);
  }

  return (
    <main className="auth">
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      {sent ? (
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }} role="status">
          <h1>تحقق من بريدك</h1>
          <p>إذا كان البريد مسجّلاً لمسوّق في حداء فستصله رسالة فيها رابط لتعيين كلمة المرور، صالح لمدة ساعة.</p>
          <Link className="btn" href="/partner/login">العودة لتسجيل الدخول</Link>
        </div>
      ) : (
        <form className="card" onSubmit={submit}>
          <h1>نسيت كلمة المرور</h1>
          <label className="f" htmlFor="email">بريدك في بوابة المسوّقين
            <input id="email" name="email" type="email" autoComplete="email" required />
          </label>
          <button className="btn primary" disabled={busy}>{busy ? "جارٍ الإرسال…" : "أرسل الرابط"}</button>
          <p className="small muted"><Link href="/partner/login">العودة لتسجيل الدخول</Link></p>
        </form>
      )}
    </main>
  );
}
