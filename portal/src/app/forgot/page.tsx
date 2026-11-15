"use client";

import Link from "next/link";
import { useState } from "react";
import { Logo } from "@/components/Logo";
import { LangToggle, useLang } from "@/components/LangProvider";
import { Field, Form, Submit } from "@/components/Form";
import { api } from "@/lib/api";

export default function ForgotPage() {
  const { t, en } = useLang();
  const [sent, setSent] = useState("");

  async function send(f: FormData) {
    const email = String(f.get("email") ?? "").trim();
    const r = await api<{ message: string }>("/auth/forgot", { method: "POST", json: { email } });
    setSent(en ? "If this email belongs to an account, we've sent it a link to set a new password." : r.message);
  }

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      {sent ? (
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>{t("تحقق من بريدك", "Check your email")}</h1>
          <p>{sent}</p>
          <p className="small muted">
            {t(<>لم يصلك شيء؟ تحقق من الرسائل غير المرغوبة، أو <Link href="/contact">تواصل معنا</Link>.</>,
               <>Nothing arrived? Check your spam folder, or <Link href="/contact">contact us</Link>.</>)}
          </p>
          <Link className="btn" href="/login">{t("رجوع لتسجيل الدخول", "Back to sign in")}</Link>
        </div>
      ) : (
        <Form className="card" onSubmit={send}>
          <h1>{t("نسيت كلمة المرور", "Forgot your password")}</h1>
          <p className="small muted">{t("اكتب بريدك المسجّل وسنرسل لك رابطاً لتعيين كلمة مرور جديدة.", "Enter the email you registered with and we'll send you a link to set a new password.")}</p>
          <Field name="email" label={t("البريد الإلكتروني", "Email")} hint={t("البريد الذي سجّلت به في حداء.", "The email you registered with on Hidaa.")}>
            <input type="email" autoComplete="email" maxLength={200} placeholder="name@example.com" required
              data-required={t("اكتب بريدك المسجّل.", "Enter your registered email.")} />
          </Field>
          <Submit busyLabel={t("جارٍ الإرسال…", "Sending…")}>{t("أرسل الرابط", "Send the link")}</Submit>
          <p className="small muted"><Link href="/login">{t("رجوع لتسجيل الدخول", "Back to sign in")}</Link></p>
        </Form>
      )}
    </main>
  );
}
