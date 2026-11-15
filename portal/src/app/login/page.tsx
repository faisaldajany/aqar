"use client";

import Link from "next/link";
import { Logo } from "@/components/Logo";
import { useRouter } from "next/navigation";
import { LangToggle, useLang } from "@/components/LangProvider";
import { Field, Form, Submit } from "@/components/Form";
import { api } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const { t } = useLang();

  async function signIn(form: FormData) {
    await api("/auth/login", { method: "POST", json: { email: form.get("email"), password: form.get("password") } });
    router.replace("/dashboard");
  }

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <Form className="card" onSubmit={signIn}>
        <h1>{t("تسجيل الدخول", "Sign in")}</h1>
        <Field name="email" label={t("البريد الإلكتروني", "Email")}>
          <input type="email" autoComplete="email" placeholder="name@example.com" maxLength={200} required
            data-required={t("اكتب بريدك الذي سجّلت به.", "Enter the email you registered with.")} />
        </Field>
        <Field name="password"
          label={<span className="lbl">{t("كلمة المرور", "Password")} <Link href="/forgot" className="small">{t("نسيت كلمة المرور؟", "Forgot your password?")}</Link></span>}>
          <input type="password" autoComplete="current-password" maxLength={128} required
            placeholder={t("كلمة مرور حسابك في حداء", "Your Hidaa account password")}
            data-required={t("اكتب كلمة المرور.", "Enter your password.")} />
        </Field>
        <Submit busyLabel={t("جارٍ الدخول…", "Signing in…")}>{t("دخول", "Sign in")}</Submit>
        <p className="small muted">
          {t("مكتب جديد؟", "New office?")} <Link href="/signup">{t("سجّل مكتبك", "Register your office")}</Link> · <Link href="/contact">{t("تواصل معنا", "Contact us")}</Link>
        </p>
      </Form>
    </main>
  );
}
