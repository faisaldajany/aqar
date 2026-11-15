"use client";

import Link from "next/link";
import { Logo } from "@/components/Logo";
import { useEffect, useState } from "react";
import { LangToggle, useLang } from "@/components/LangProvider";
import { Field, Form, Submit } from "@/components/Form";
import { api, saudiMobile, type Me } from "@/lib/api";

/** The support inbox's topics are stored in Arabic; English pages show these names for them. */
const TOPICS_EN: Record<string, string> = {
  "سؤال عن الخدمة": "Question about the service",
  "مشكلة تقنية": "Technical problem",
  "الاشتراك والفواتير": "Subscription and invoices",
  "ربط واتساب": "Connecting WhatsApp",
  "اقتراح": "Suggestion",
  "أخرى": "Other",
};

/** Public support form. Signed-in offices get their details filled in. */
export default function ContactPage() {
  const { t, en } = useLang();
  const [topics, setTopics] = useState<string[]>([]);
  const [me, setMe] = useState<Me | null>(null);
  const [ref, setRef] = useState("");

  useEffect(() => {
    api<string[]>("/public/support/topics").then(setTopics).catch(() => setTopics(["أخرى"]));
    api<Me>("/me").then(setMe).catch(() => undefined);
  }, []);

  async function send(f: FormData) {
    const r = await api<{ reference?: string }>("/public/support", {
      method: "POST",
      json: {
        name: f.get("name"), email: f.get("email"), phone: f.get("phone"), topic: f.get("topic"),
        message: f.get("message"), office: me?.office.name ?? f.get("office"), website: f.get("website"), source: "portal",
      },
    });
    setRef(r.reference ?? t("تم", "Done"));
  }

  /** The server needs one way to reply; say so under both fields, and check the mobile's shape. */
  function check(f: FormData): Record<string, string> {
    const email = String(f.get("email") ?? "").trim(), phone = String(f.get("phone") ?? "").trim();
    if (!email && !phone) {
      const both = t("اكتب بريدك أو جوالك لنرد عليك.", "Enter your email or mobile so we can reply.");
      return { email: both, phone: both };
    }
    if (phone && !saudiMobile(phone) && !/^\+?[0-9\s-]{9,16}$/.test(phone)) {
      return { phone: t("رقم الجوال غير صحيح، مثل 0512345678.", "Not a valid mobile number, e.g. 0512345678.") };
    }
    return {};
  }

  if (ref) {
    return (
      <main className="auth">
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>{t("وصلتنا رسالتك", "We got your message")}</h1>
          <p>
            {t(<>رقم الطلب <span className="mono">{ref}</span>. نرد عليك خلال يوم عمل على البريد أو الجوال الذي كتبته.</>,
               <>Request number <span className="mono">{ref}</span>. We&apos;ll reply within one working day to the email or mobile you gave.</>)}
          </p>
          <Link className="btn" href={me ? "/dashboard" : "/login"}>{t("رجوع", "Back")}</Link>
        </div>
      </main>
    );
  }

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <Form className="card" onSubmit={send} validate={check}
        fieldFor={{ no_name: "name", bad_email: "email", bad_phone: "phone", no_contact: "email", short: "message" }}>
        <h1>{t("تواصل مع دعم حداء", "Contact Hidaa support")}</h1>
        <p className="small muted">{t("اكتب سؤالك أو المشكلة، ونرد عليك خلال يوم عمل.", "Write your question or the problem, and we'll reply within one working day.")}</p>
        <Field name="name" label={t("الاسم", "Name")}>
          <input type="text" required maxLength={120} autoComplete="name" defaultValue={me?.user.fullName} key={me?.user.id}
            placeholder={t("مثال: فهد العتيبي", "e.g. Fahad Alotaibi")} data-required={t("اكتب اسمك لنعرف من نخاطب.", "Enter your name so we know who to address.")} />
        </Field>
        {!me && (
          <Field name="office" label={t("اسم المكتب", "Office name")} optional>
            <input type="text" maxLength={200} autoComplete="organization" placeholder={t("مثال: مكتب الريادة للعقارات", "e.g. Al Riyada Real Estate")} />
          </Field>
        )}
        <Field name="email" label={t("البريد الإلكتروني", "Email")} hint={t("البريد أو الجوال: واحد منهما يكفي.", "Email or mobile: one of them is enough.")}>
          <input type="email" dir="ltr" maxLength={200} autoComplete="email" defaultValue={me?.user.email} key={`e${me?.user.id}`} placeholder="name@example.com" />
        </Field>
        <Field name="phone" label={t("الجوال", "Mobile")}>
          <input type="tel" dir="ltr" inputMode="tel" autoComplete="tel" maxLength={30} placeholder="0512345678" />
        </Field>
        <Field name="topic" label={t("الموضوع", "Topic")}>
          <select>{topics.map((x) => <option key={x} value={x}>{en ? TOPICS_EN[x] ?? x : x}</option>)}</select>
        </Field>
        <Field name="message" label={t("الرسالة", "Message")} hint={t("كلما ذكرت تفاصيل أكثر (الصفحة، وما ظهر لك) رددنا أسرع.", "The more detail (the page, what you saw), the faster we can help.")}>
          <textarea rows={5} required minLength={10} maxLength={4000}
            placeholder={t("مثال: حاولت ربط رقم المكتب وظهرت رسالة «الرقم غير مؤهل».", "e.g. I tried to connect the office number and got \"number not eligible\".")}
            data-required={t("اكتب رسالتك.", "Write your message.")} />
        </Field>
        {/* Hidden from people; bots fill it in. */}
        <input type="text" name="website" tabIndex={-1} autoComplete="off" aria-hidden="true" style={{ position: "absolute", width: 1, height: 1, margin: -1, padding: 0, border: 0, overflow: "hidden", clipPath: "inset(50%)", opacity: 0, pointerEvents: "none" }} />
        <Submit busyLabel={t("جارٍ الإرسال…", "Sending…")}>{t("إرسال", "Send")}</Submit>
        <p className="small muted"><Link href={me ? "/dashboard" : "/login"}>{t("رجوع", "Back")}</Link></p>
      </Form>
    </main>
  );
}
