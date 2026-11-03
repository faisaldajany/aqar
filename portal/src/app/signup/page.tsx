"use client";

import Link from "next/link";
import { Logo } from "@/components/Logo";
import { useRouter, useSearchParams } from "next/navigation";
import { FormEvent, Suspense, useState } from "react";
import { api, ApiError, latinDigits } from "@/lib/api";

export default function SignupPage() {
  return <Suspense fallback={null}><SignupForm /></Suspense>;
}

type Field = "officeName" | "falLicense" | "fullName" | "email" | "password" | "promoCode";
const ORDER: Field[] = ["officeName", "falLicense", "fullName", "email", "password", "promoCode"];
type Errors = Partial<Record<Field, string>>;
const LABELS: Record<Field, string> = {
  officeName: "اسم المكتب", falLicense: "رقم رخصة فال", fullName: "الاسم",
  email: "البريد الإلكتروني", password: "كلمة المرور", promoCode: "كود الخصم",
};

const REQUIRED = "هذا الحقل مطلوب.";
const FAL_RULE = "«رقم رخصة فال»: أرقام فقط، من 7 إلى 12 رقماً.";

/** Arabic digits as 0-9, without the invisible direction marks a pasted number often carries. */
function cleanFal(raw: string) {
  return latinDigits(raw).replace(/[​-‏‪-‮⁦-⁩﻿]/g, "").trim();
}

function check(v: Record<Field, string>): Errors {
  const e: Errors = {};
  if (!v.officeName.trim()) e.officeName = `«اسم المكتب»: ${REQUIRED}`;
  else if (v.officeName.trim().length > 120) e.officeName = "«اسم المكتب»: 120 حرفاً على الأكثر.";
  const fal = cleanFal(v.falLicense);
  const falDigits = fal.replace(/\D/g, "");
  if (!fal) e.falLicense = `«رقم رخصة فال»: ${REQUIRED}`;
  else if (!/^[0-9\s-]+$/.test(fal) || falDigits.length < 7 || falDigits.length > 12) e.falLicense = FAL_RULE;
  if (!v.fullName.trim()) e.fullName = `«الاسم»: ${REQUIRED}`;
  else if (v.fullName.trim().length > 120) e.fullName = "«الاسم»: 120 حرفاً على الأكثر.";
  if (!v.email.trim()) e.email = `«البريد الإلكتروني»: ${REQUIRED}`;
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email.trim())) e.email = "«البريد الإلكتروني»: الصيغة غير صحيحة، مثل name@example.com";
  if (!v.password) e.password = `«كلمة المرور»: ${REQUIRED}`;
  else if (v.password.length < 8) e.password = "«كلمة المرور»: 8 أحرف على الأقل.";
  else if (v.password.length > 128) e.password = "«كلمة المرور»: 128 حرفاً على الأكثر.";
  if (v.promoCode.trim().length > 40) e.promoCode = "«كود الخصم»: 40 حرفاً على الأكثر.";
  return e;
}

/** Server errors that belong to one field. */
function fieldOf(code: string): Field | null {
  if (code === "email_taken") return "email";
  if (code === "bad_fal") return "falLicense";
  if (code.startsWith("promo_")) return "promoCode";
  return null;
}

/** Under the field the label is already there, so "«كلمة المرور»: 8 أحرف…" shows as "8 أحرف…". */
const short = (m: string) => m.replace(/^«[^»]*»:\s*/, "");

function SignupForm() {
  const router = useRouter();
  const promo = useSearchParams().get("promo") ?? "";
  const [error, setError] = useState("");
  const [errors, setErrors] = useState<Errors>({});
  const [busy, setBusy] = useState(false);

  /** Marks each wrong field, says under the button which ones they are, and moves to the first. */
  function show(found: Errors, form: HTMLFormElement) {
    setErrors(found);
    const wrong = ORDER.filter((k) => found[k]);
    if (!wrong.length) return;
    const one = found[wrong[0]]!;
    setError(wrong.length === 1
      ? (one.startsWith("«") ? one : `«${LABELS[wrong[0]]}»: ${one}`)
      : `راجع الحقول المظللة: ${wrong.map((k) => `«${LABELS[k]}»`).join("، ")}.`);
    (form.elements.namedItem(wrong[0]) as HTMLInputElement | null)?.focus();
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const f = new FormData(form);
    const v = Object.fromEntries(ORDER.map((k) => [k, String(f.get(k) ?? "")])) as Record<Field, string>;
    setError("");
    const found = check(v);
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
        setError("تعذّر إنشاء الحساب.");
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
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <form className="card" onSubmit={submit} onInput={edited} noValidate>
        <h1>سجّل مكتبك</h1>
        <p className="small muted">بعد التسجيل تربط رقم واتساب المكتب مباشرة، دون زيارة.</p>
        <label className="f" htmlFor="officeName">اسم المكتب
          <input id="officeName" name="officeName" type="text" required maxLength={120} {...invalid("officeName")} />
          {hint("officeName")}
        </label>
        <label className="f" htmlFor="falLicense">رقم رخصة فال
          <input id="falLicense" name="falLicense" type="text" inputMode="numeric" required autoComplete="off" {...invalid("falLicense")} />
          {hint("falLicense")}
        </label>
        <label className="f" htmlFor="fullName">اسمك
          <input id="fullName" name="fullName" type="text" required maxLength={120} autoComplete="name" {...invalid("fullName")} />
          {hint("fullName")}
        </label>
        <label className="f" htmlFor="email">البريد الإلكتروني
          <input id="email" name="email" type="email" autoComplete="email" required {...invalid("email")} />
          {hint("email")}
        </label>
        <label className="f" htmlFor="password">كلمة المرور <span className="small muted">8 أحرف على الأقل</span>
          <input id="password" name="password" type="password" autoComplete="new-password" required minLength={8} {...invalid("password")} />
          {hint("password")}
        </label>
        <label className="f" htmlFor="promoCode">كود الخصم <span className="small muted">اختياري</span>
          <input id="promoCode" name="promoCode" type="text" dir="ltr" maxLength={40} autoComplete="off" defaultValue={promo} {...invalid("promoCode")} />
          {hint("promoCode")}
        </label>
        {error && <p className="err" role="alert">{error}</p>}
        <button className="btn primary" disabled={busy}>{busy ? "جارٍ الإنشاء…" : "أنشئ الحساب"}</button>
        <p className="small muted">لديك حساب؟ <Link href="/login">سجّل الدخول</Link></p>
      </form>
    </main>
  );
}
