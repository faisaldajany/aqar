"use client";

import Link from "next/link";
import { Logo } from "@/components/Logo";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { LangToggle, useLang } from "@/components/LangProvider";
import { Field as FormField, Form, Submit } from "@/components/Form";
import { api, latinDigits } from "@/lib/api";

export default function SignupPage() {
  return <Suspense fallback={null}><SignupForm /></Suspense>;
}

type Field = "officeName" | "falLicense" | "fullName" | "email" | "password" | "promoCode";
const ORDER: Field[] = ["officeName", "falLicense", "fullName", "email", "password", "promoCode"];
type Errors = Partial<Record<Field, string>>;
type T = <V>(ar: V, en: V) => V;

/** Arabic digits as 0-9, without the invisible direction marks a pasted number often carries. */
function cleanFal(raw: string) {
  return latinDigits(raw).replace(/[​-‏‪-‮⁦-⁩﻿]/g, "").trim();
}

/** The page's own checks; each message shows under its field. */
function check(v: Record<Field, string>, t: T): Errors {
  const required: Record<Field, string> = {
    officeName: t("اكتب اسم المكتب كما يظهر للعملاء.", "Enter the office name as customers see it."),
    falLicense: t("اكتب رقم رخصة فال.", "Enter the FAL licence number."),
    fullName: t("اكتب اسمك.", "Enter your name."),
    email: t("اكتب بريدك الإلكتروني.", "Enter your email."),
    password: t("اختر كلمة مرور.", "Choose a password."),
    promoCode: "",
  };
  const e: Errors = {};
  for (const k of ORDER) if (k !== "promoCode" && !v[k].trim()) e[k] = required[k];
  if (!e.officeName && v.officeName.trim().length > 120) e.officeName = t("120 حرفاً على الأكثر.", "120 characters at most.");
  const fal = cleanFal(v.falLicense);
  const falDigits = fal.replace(/\D/g, "");
  if (!e.falLicense && !/^[0-9\s-]+$/.test(fal)) {
    e.falLicense = t("أرقام فقط، بلا حروف أو رموز.", "Digits only, no letters or symbols.");
  } else if (!e.falLicense && (falDigits.length < 7 || falDigits.length > 12)) {
    e.falLicense = t(`من 7 إلى 12 رقماً، وكتبت ${falDigits.length}.`, `7 to 12 digits; you typed ${falDigits.length}.`);
  }
  if (!e.fullName && v.fullName.trim().length > 120) e.fullName = t("120 حرفاً على الأكثر.", "120 characters at most.");
  if (!e.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email.trim())) {
    e.email = t("الصيغة غير صحيحة، مثل name@example.com", "Not a valid address, e.g. name@example.com");
  }
  if (!e.password && v.password.length < 8) e.password = t(`8 أحرف على الأقل (كتبت ${v.password.length}).`, `At least 8 characters (you typed ${v.password.length}).`);
  else if (!e.password && v.password.length > 128) e.password = t("128 حرفاً على الأكثر.", "128 characters at most.");
  if (v.promoCode.trim().length > 40) e.promoCode = t("40 حرفاً على الأكثر.", "40 characters at most.");
  return e;
}

/** Server errors that belong to one field. */
function fieldOf(code: string): Field | undefined {
  if (code === "email_taken") return "email";
  if (code === "bad_fal") return "falLicense";
  if (code.startsWith("promo_")) return "promoCode";
  return undefined;
}

function SignupForm() {
  const router = useRouter();
  const { t } = useLang();
  const promo = useSearchParams().get("promo") ?? "";
  const values = (f: FormData) => Object.fromEntries(ORDER.map((k) => [k, String(f.get(k) ?? "")])) as Record<Field, string>;

  async function create(f: FormData) {
    const v = values(f);
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
  }

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <Form className="card" onSubmit={create} validate={(f) => check(values(f), t)} fieldFor={fieldOf}>
        <h1>{t("سجّل مكتبك", "Register your office")}</h1>
        <p className="small muted">{t("بعد التسجيل تربط رقم واتساب المكتب مباشرة، دون زيارة.", "After signing up you connect the office's WhatsApp number straight away, with no visit needed.")}</p>
        <FormField name="officeName" label={t("اسم المكتب", "Office name")} hint={t("كما يظهر لعملائك في واتساب.", "As your customers will see it on WhatsApp.")}>
          <input type="text" maxLength={120} autoComplete="organization" placeholder={t("مثال: مكتب الريادة للعقارات", "e.g. Al Riyada Real Estate")} />
        </FormField>
        <FormField name="falLicense" label={t("رقم رخصة فال", "FAL licence number")} hint={t("من رخصتك في الهيئة العامة للعقار، من 7 إلى 12 رقماً.", "From your REGA licence, 7 to 12 digits.")}>
          <input type="text" inputMode="numeric" autoComplete="off" maxLength={20} placeholder={t("مثال: 1100012345", "e.g. 1100012345")} />
        </FormField>
        <FormField name="fullName" label={t("اسمك", "Your name")}>
          <input type="text" maxLength={120} autoComplete="name" placeholder={t("مثال: فهد العتيبي", "e.g. Fahad Alotaibi")} />
        </FormField>
        <FormField name="email" label={t("البريد الإلكتروني", "Email")} hint={t("نرسل إليه رمز تأكيد الحساب.", "We'll send the account confirmation code to it.")}>
          <input type="email" autoComplete="email" maxLength={200} placeholder="name@example.com" />
        </FormField>
        <FormField name="password" label={t("كلمة المرور", "Password")} hint={t("8 أحرف على الأقل. اخلط الحروف والأرقام لتكون أقوى.", "At least 8 characters. Mix letters and numbers to make it stronger.")}>
          <input type="password" autoComplete="new-password" maxLength={128} placeholder={t("اختر كلمة مرور لحسابك", "Choose a password for your account")} />
        </FormField>
        <FormField name="promoCode" label={t("كود الخصم", "Promo code")} optional hint={t("إن أعطاك مسوّق حداء كوداً.", "If a Hidaa marketer gave you one.")}>
          <input type="text" dir="ltr" maxLength={40} autoComplete="off" defaultValue={promo} placeholder={t("اكتبه كما وصلك", "Type it as you received it")} />
        </FormField>
        <Submit busyLabel={t("جارٍ الإنشاء…", "Creating…")}>{t("أنشئ الحساب", "Create account")}</Submit>
        <p className="small muted">{t("لديك حساب؟", "Already have an account?")} <Link href="/login">{t("سجّل الدخول", "Sign in")}</Link></p>
      </Form>
    </main>
  );
}
