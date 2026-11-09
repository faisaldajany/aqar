"use client";

import Link from "next/link";
import { Logo } from "@/components/Logo";
import { FormEvent, useEffect, useState } from "react";
import { LangToggle, useLang } from "@/components/LangProvider";
import { api, ApiError, type Me } from "@/lib/api";

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
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<string[]>("/public/support/topics").then(setTopics).catch(() => setTopics(["أخرى"]));
    api<Me>("/me").then(setMe).catch(() => undefined);
  }, []);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setError("");
    try {
      const r = await api<{ reference?: string }>("/public/support", {
        method: "POST",
        json: {
          name: f.get("name"), email: f.get("email"), phone: f.get("phone"), topic: f.get("topic"),
          message: f.get("message"), office: me?.office.name ?? f.get("office"), website: f.get("website"), source: "portal",
        },
      });
      setRef(r.reference ?? t("تم", "Done"));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t("تعذّر الإرسال. حاول مرة أخرى.", "Couldn't send. Please try again."));
    } finally {
      setBusy(false);
    }
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
      <form className="card" onSubmit={submit}>
        <h1>{t("تواصل مع دعم حداء", "Contact Hidaa support")}</h1>
        <p className="small muted">{t("اكتب سؤالك أو المشكلة، ونرد عليك خلال يوم عمل.", "Write your question or the problem, and we'll reply within one working day.")}</p>
        <label className="f" htmlFor="name">{t("الاسم", "Name")}<input id="name" name="name" type="text" required maxLength={120} defaultValue={me?.user.fullName} key={me?.user.id} /></label>
        {!me && <label className="f" htmlFor="office">{t("اسم المكتب (اختياري)", "Office name (optional)")}<input id="office" name="office" type="text" maxLength={200} /></label>}
        <label className="f" htmlFor="email">{t("البريد الإلكتروني", "Email")}<input id="email" name="email" type="email" dir="ltr" maxLength={200} defaultValue={me?.user.email} key={`e${me?.user.id}`} /></label>
        <label className="f" htmlFor="phone">{t("الجوال", "Mobile")} <span className="small muted">{t("البريد أو الجوال مطلوب", "Email or mobile is required")}</span><input id="phone" name="phone" type="tel" dir="ltr" placeholder="05xxxxxxxx" maxLength={30} /></label>
        <label className="f" htmlFor="topic">{t("الموضوع", "Topic")}
          <select id="topic" name="topic">{topics.map((x) => <option key={x} value={x}>{en ? TOPICS_EN[x] ?? x : x}</option>)}</select></label>
        <label className="f" htmlFor="message">{t("الرسالة", "Message")}<textarea id="message" name="message" rows={5} required minLength={10} maxLength={4000} /></label>
        {/* Hidden from people; bots fill it in. */}
        <input type="text" name="website" tabIndex={-1} autoComplete="off" aria-hidden="true" style={{ position: "absolute", width: 1, height: 1, margin: -1, padding: 0, border: 0, overflow: "hidden", clipPath: "inset(50%)", opacity: 0, pointerEvents: "none" }} />
        {error && <p className="err" role="alert">{error}</p>}
        <button className="btn primary" disabled={busy}>{busy ? t("جارٍ الإرسال…", "Sending…") : t("إرسال", "Send")}</button>
        <p className="small muted"><Link href={me ? "/dashboard" : "/login"}>{t("رجوع", "Back")}</Link></p>
      </form>
    </main>
  );
}
