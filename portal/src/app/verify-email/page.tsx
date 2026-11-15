"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Logo } from "@/components/Logo";
import { LangToggle, useLang } from "@/components/LangProvider";
import { DoneNote, ErrorNote, Field, Form, Submit } from "@/components/Form";
import { api, ApiError, type Me } from "@/lib/api";

/** After signup: type the 6-digit code from the email (or click its link) before using the portal. */
export default function VerifyEmailPage() {
  const router = useRouter();
  const { t } = useLang();
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [resendError, setResendError] = useState<unknown>(null);
  const [note, setNote] = useState("");
  const [wait, setWait] = useState(60);

  useEffect(() => {
    api<Me>("/me").then((m) => {
      if (m.user.emailVerified !== false) router.replace("/dashboard");
      else setEmail(m.user.email);
    }).catch((e) => {
      if (e instanceof ApiError && e.status === 401) router.replace("/login");
    });
  }, [router]);

  useEffect(() => {
    if (wait <= 0) return;
    const id = setTimeout(() => setWait((w) => w - 1), 1000);
    return () => clearTimeout(id);
  }, [wait]);

  async function confirm() {
    setNote("");
    await api("/verify-email", { method: "POST", json: { code } });
    router.replace("/dashboard");
  }

  async function resend() {
    setResendError(null);
    setNote("");
    try {
      const r = await api<{ sent: boolean; verified: boolean }>("/verify-email/resend", { method: "POST" });
      if (r.verified) { router.replace("/dashboard"); return; }
      setNote(t("أرسلنا رسالة جديدة فيها رمز جديد. الرمز السابق لم يعد يعمل.", "We've sent a new email with a new code. The previous code no longer works."));
      setCode("");
      setWait(60);
    } catch (err) {
      setResendError(err);
    }
  }

  async function logout() {
    await api("/auth/logout", { method: "POST" }).catch(() => undefined);
    router.replace("/login");
  }

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <Form className="card" onSubmit={confirm} fieldFor={{ code_wrong: "code", bad_code: "code", code_expired: "code" }}
        validate={(): Record<string, string> => code.length === 6 ? {} : {
          code: code ? t(`الرمز 6 أرقام، وكتبت ${code.length}.`, `The code is 6 digits; you typed ${code.length}.`)
            : t("اكتب الرمز الذي وصلك في البريد.", "Type the code from the email."),
        }}>
        <h1>{t("أكّد بريدك", "Confirm your email")}</h1>
        <p className="small">
          {t(<>أرسلنا رمزاً من 6 أرقام إلى <b dir="ltr">{email || "…"}</b>. اكتبه هنا، أو اضغط زر «تأكيد البريد» في الرسالة.</>,
             <>We sent a 6-digit code to <b>{email || "…"}</b>. Type it here, or press the button in the email.</>)}
        </p>
        <Field name="code" label={t("رمز التحقق", "Verification code")}
          hint={t("6 أرقام، صالح لمدة 30 دقيقة. لم تصلك الرسالة؟ راجع مجلد الرسائل غير المرغوبة.", "6 digits, valid for 30 minutes. No email? Check your spam folder.")}>
          <input inputMode="numeric" autoComplete="one-time-code" dir="ltr" maxLength={7}
                 value={code} onChange={(e) => setCode(e.target.value.replace(/[^0-9٠-٩]/g, "").slice(0, 6))}
                 style={{ fontSize: 26, letterSpacing: 8, textAlign: "center", fontFamily: "ui-monospace, Menlo, monospace" }}
                 placeholder="••••••" />
        </Field>
        <Submit busyLabel={t("جارٍ التحقق…", "Verifying…")}>{t("تأكيد", "Confirm")}</Submit>
        {note && <DoneNote>{note}</DoneNote>}
        {resendError != null && <ErrorNote error={resendError} />}
        <div className="row" style={{ justifyContent: "space-between" }}>
          <button type="button" className="linkbtn small" disabled={wait > 0} onClick={() => void resend()}>
            {wait > 0 ? t(`أرسل الرمز مرة أخرى بعد ${wait} ث`, `Send the code again in ${wait}s`) : t("أرسل الرمز مرة أخرى", "Send the code again")}
          </button>
          <button type="button" className="linkbtn small" onClick={() => void logout()}>{t("تسجيل الخروج", "Sign out")}</button>
        </div>
        <p className="small muted" style={{ margin: 0 }}>
          {t("البريد خطأ؟ سجّل الخروج وأنشئ الحساب من جديد، أو راسلنا على support@hidaaco.com.", "Wrong email? Sign out and create the account again, or write to support@hidaaco.com.")}
        </p>
      </Form>
    </main>
  );
}
