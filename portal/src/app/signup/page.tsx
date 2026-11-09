"use client";

import Link from "next/link";
import { Logo } from "@/components/Logo";
import { useRouter, useSearchParams } from "next/navigation";
import { FormEvent, Suspense, useState } from "react";
import { LangToggle, useLang } from "@/components/LangProvider";
import { api, ApiError, latinDigits } from "@/lib/api";

export default function SignupPage() {
  return <Suspense fallback={null}><SignupForm /></Suspense>;
}

type Field = "officeName" | "falLicense" | "fullName" | "email" | "password" | "promoCode";
const ORDER: Field[] = ["officeName", "falLicense", "fullName", "email", "password", "promoCode"];
type Errors = Partial<Record<Field, string>>;
type T = <V>(ar: V, en: V) => V;

const labels = (t: T): Record<Field, string> => ({
  officeName: t("اسم المكتب", "Office name"), falLicense: t("رقم رخصة فال", "FAL licence number"), fullName: t("الاسم", "Name"),
  email: t("البريد الإلكتروني", "Email"), password: t("كلمة المرور", "Password"), promoCode: t("كود الخصم", "Promo code"),
});

/** Arabic digits as 0-9, without the invisible direction marks a pasted number often carries. */
function cleanFal(raw: string) {
  return latinDigits(raw).replace(/[​-‏‪-‮⁦-⁩﻿]/g, "").trim();
}

function check(v: Record<Field, string>, t: T): Errors {
  const L = labels(t);
  const q = (k: Field, msg: string) => `«${L[k]}»: ${msg}`;
  const required = t("هذا الحقل مطلوب.", "This field is required.");
  const e: Errors = {};
  if (!v.officeName.trim()) e.officeName = q("officeName", required);
  else if (v.officeName.trim().length > 120) e.officeName = q("officeName", t("120 حرفاً على الأكثر.", "120 characters at most."));
  const fal = cleanFal(v.falLicense);
  const falDigits = fal.replace(/\D/g, "");
  if (!fal) e.falLicense = q("falLicense", required);
  else if (!/^[0-9\s-]+$/.test(fal) || falDigits.length < 7 || falDigits.length > 12) {
    e.falLicense = q("falLicense", t("أرقام فقط، من 7 إلى 12 رقماً.", "digits only, 7 to 12 of them."));
  }
  if (!v.fullName.trim()) e.fullName = q("fullName", required);
  else if (v.fullName.trim().length > 120) e.fullName = q("fullName", t("120 حرفاً على الأكثر.", "120 characters at most."));
  if (!v.email.trim()) e.email = q("email", required);
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email.trim())) {
    e.email = q("email", t("الصيغة غير صحيحة، مثل name@example.com", "not a valid address, e.g. name@example.com"));
  }
  if (!v.password) e.password = q("password", required);
  else if (v.password.length < 8) e.password = q("password", t("8 أحرف على الأقل.", "at least 8 characters."));
  else if (v.password.length > 128) e.password = q("password", t("128 حرفاً على الأكثر.", "128 characters at most."));
  if (v.promoCode.trim().length > 40) e.promoCode = q("promoCode", t("40 حرفاً على الأكثر.", "40 characters at most."));
  return e;
}

/** Server errors that belong to one field. */
function fieldOf(code: string): Field | null {
  if (code === "email_taken") return "email";
  if (code === "bad_fal") return "falLicense";
  if (code.startsWith("promo_")) return "promoCode";
  return null;
}

/** Under the field the label is already there, so "«Password»: at least 8…" shows as "at least 8…". */
const short = (m: string) => m.replace(/^«[^»]*»:\s*/, "").replace(/^./, (c) => c.toUpperCase());

function SignupForm() {
  const router = useRouter();
  const { t } = useLang();
  const promo = useSearchParams().get("promo") ?? "";
  const [error, setError] = useState("");
  const [errors, setErrors] = useState<Errors>({});
  const [busy, setBusy] = useState(false);

  /** Marks each wrong field, says under the button which ones they are, and moves to the first. */
  function show(found: Errors, form: HTMLFormElement) {
    setErrors(found);
    const wrong = ORDER.filter((k) => found[k]);
    if (!wrong.length) return;
    const L = labels(t);
    const one = found[wrong[0]]!;
    setError(wrong.length === 1
      ? (one.startsWith("«") ? one : `«${L[wrong[0]]}»: ${one}`)
      : t(`راجع الحقول المظللة: ${wrong.map((k) => `«${L[k]}»`).join("، ")}.`,
          `Check the highlighted fields: ${wrong.map((k) => `«${L[k]}»`).join(", ")}.`));
    (form.elements.namedItem(wrong[0]) as HTMLInputElement | null)?.focus();
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const f = new FormData(form);
    const v = Object.fromEntries(ORDER.map((k) => [k, String(f.get(k) ?? "")])) as Record<Field, string>;
    setError("");
    const found = check(v, t);
    if (Object.keys(found).length) return show(found, form);
    setErrors({});
    setBusy(true);
    try {
      await api("/auth/signup", {
        method: "POST",
        json: {
          officeName: v.officeName.trim(),
          falLicense: cleanFal(v.falLicense),
          fullName: v.fullName.trim(),
          email: v.email.trim(),
          password: v.password,
          promoCode: v.promoCode.trim() || null,
        },
      });
      router.replace("/verify-email");
    } catch (err) {
      if (!(err instanceof ApiError)) {
        setError(t("تعذّر إنشاء الحساب.", "Couldn't create the account."));
        return;
      }
      const server: Errors = {};
      for (const k of ORDER) if (err.fields[k]) server[k] = err.fields[k];
      const one = fieldOf(err.code);
      if (one) server[one] = err.message;
      if (Object.keys(server).length) show(server, form);
      else setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  /** Editing a field clears its message. */
  function edited(e: FormEvent<HTMLFormElement>) {
    const name = (e.target as HTMLInputElement).name as Field;
    if (errors[name]) setErrors(({ [name]: _gone, ...rest }) => rest);
  }

  const invalid = (k: Field) => (errors[k] ? { "aria-invalid": true, "aria-describedby": `${k}-err` } : {});
  const hint = (k: Field) => errors[k] && <span id={`${k}-err`} className="err">{short(errors[k]!)}</span>;

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <form className="card" onSubmit={submit} onInput={edited} noValidate>
        <h1>{t("سجّل مكتبك", "Register your office")}</h1>
        <p className="small muted">{t("بعد التسجيل تربط رقم واتساب المكتب مباشرة، دون زيارة.", "After signing up you connect the office's WhatsApp number straight away, with no visit needed.")}</p>
        <label className="f" htmlFor="officeName">{t("اسم المكتب", "Office name")}
          <input id="officeName" name="officeName" type="text" required maxLength={120} {...invalid("officeName")} />
          {hint("officeName")}
        </label>
        <label className="f" htmlFor="falLicense">{t("رقم رخصة فال", "FAL licence number")}
          <input id="falLicense" name="falLicense" type="text" inputMode="numeric" required autoComplete="off" {...invalid("falLicense")} />
          {hint("falLicense")}
        </label>
        <label className="f" htmlFor="fullName">{t("اسمك", "Your name")}
          <input id="fullName" name="fullName" type="text" required maxLength={120} autoComplete="name" {...invalid("fullName")} />
          {hint("fullName")}
        </label>
        <label className="f" htmlFor="email">{t("البريد الإلكتروني", "Email")}
          <input id="email" name="email" type="email" autoComplete="email" required {...invalid("email")} />
          {hint("email")}
        </label>
        <label className="f" htmlFor="password">{t("كلمة المرور", "Password")} <span className="small muted">{t("8 أحرف على الأقل", "At least 8 characters")}</span>
          <input id="password" name="password" type="password" autoComplete="new-password" required minLength={8} {...invalid("password")} />
          {hint("password")}
        </label>
        <label className="f" htmlFor="promoCode">{t("كود الخصم", "Promo code")} <span className="small muted">{t("اختياري", "Optional")}</span>
          <input id="promoCode" name="promoCode" type="text" dir="ltr" maxLength={40} autoComplete="off" defaultValue={promo} {...invalid("promoCode")} />
          {hint("promoCode")}
        </label>
        {error && <p className="err" role="alert">{error}</p>}
        <button className="btn primary" disabled={busy}>{busy ? t("جارٍ الإنشاء…", "Creating…") : t("أنشئ الحساب", "Create account")}</button>
        <p className="small muted">{t("لديك حساب؟", "Already have an account?")} <Link href="/login">{t("سجّل الدخول", "Sign in")}</Link></p>
      </form>
    </main>
  );
}
