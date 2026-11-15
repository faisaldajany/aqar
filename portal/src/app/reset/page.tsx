"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { Logo } from "@/components/Logo";
import { LangToggle, useLang } from "@/components/LangProvider";
import { Field, Form, Submit } from "@/components/Form";
import { api, ApiError } from "@/lib/api";

export default function ResetPage() {
  return <Suspense fallback={null}><ResetForm /></Suspense>;
}

function ResetForm() {
  const { t } = useLang();
  const token = useSearchParams().get("token") ?? "";
  const [done, setDone] = useState(false);
  const [expired, setExpired] = useState(false);

  async function save(f: FormData) {
    setExpired(false);
    try {
      await api("/auth/reset", { method: "POST", json: { token, password: String(f.get("password") ?? "") } });
      setDone(true);
    } catch (err) {
      setExpired(err instanceof ApiError && err.code === "reset_invalid");
      throw err;
    }
  }

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      {done ? (
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>{t("تم تغيير كلمة المرور", "Password changed")}</h1>
          <p>{t("سجّل الدخول بكلمة المرور الجديدة. أي جلسة مفتوحة على جهاز آخر انتهت.", "Sign in with your new password. Any session open on another device has ended.")}</p>
          <Link className="btn primary" href="/login">{t("تسجيل الدخول", "Sign in")}</Link>
        </div>
      ) : !token ? (
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>{t("الرابط ناقص", "The link is incomplete")}</h1>
          <p>{t("افتح الرابط كاملاً من رسالة البريد، أو اطلب رابطاً جديداً.", "Open the full link from the email, or ask for a new one.")}</p>
          <Link className="btn" href="/forgot">{t("اطلب رابطاً جديداً", "Ask for a new link")}</Link>
        </div>
      ) : (
        <Form className="card" onSubmit={save} fieldFor={{ weak_password: "password" }}
          validate={(f): Record<string, string> => {
            const p = String(f.get("password") ?? ""), c = String(f.get("confirm") ?? "");
            if (p && p.length < 8) return { password: t(`8 أحرف على الأقل (كتبت ${p.length}).`, `At least 8 characters (you typed ${p.length}).`) };
            return p && c && p !== c ? { confirm: t("لا تطابق كلمة المرور الأولى. اكتبها مرة أخرى.", "Doesn't match the first password. Type it again.") } : {};
          }}>
          <h1>{t("كلمة مرور جديدة", "New password")}</h1>
          <Field name="password" label={t("كلمة المرور الجديدة", "New password")} hint={t("8 أحرف على الأقل. اخلط الحروف والأرقام لتكون أقوى.", "At least 8 characters. Mix letters and numbers to make it stronger.")}>
            <input type="password" autoComplete="new-password" maxLength={128} required placeholder={t("كلمة مرور جديدة لحسابك", "A new password for your account")}
              data-required={t("اختر كلمة مرور جديدة.", "Choose a new password.")} />
          </Field>
          <Field name="confirm" label={t("أعد كتابتها", "Type it again")}>
            <input type="password" autoComplete="new-password" maxLength={128} required placeholder={t("الكلمة نفسها مرة أخرى", "The same password again")}
              data-required={t("اكتب كلمة المرور مرة أخرى للتأكد.", "Type the password again to confirm it.")} />
          </Field>
          <Submit>{t("احفظ كلمة المرور", "Save password")}</Submit>
          {expired && <p className="small"><Link href="/forgot">{t("اطلب رابطاً جديداً", "Ask for a new link")}</Link></p>}
        </Form>
      )}
    </main>
  );
}
