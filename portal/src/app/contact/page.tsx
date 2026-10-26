"use client";

import Link from "next/link";
import { SaduMark } from "@/components/Sadu";
import { FormEvent, useEffect, useState } from "react";
import { api, ApiError, type Me } from "@/lib/api";

/** Public support form. Signed-in offices get their details filled in. */
export default function ContactPage() {
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
      setRef(r.reference ?? "تم");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّر الإرسال. حاول مرة أخرى.");
    } finally {
      setBusy(false);
    }
  }

  if (ref) {
    return (
      <main className="auth">
        <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
          <h1>وصلتنا رسالتك</h1>
          <p>رقم الطلب <span className="mono">{ref}</span>. نرد عليك خلال يوم عمل على البريد أو الجوال الذي كتبته.</p>
          <Link className="btn" href={me ? "/dashboard" : "/login"}>رجوع</Link>
        </div>
      </main>
    );
  }

  return (
    <main className="auth">
      <Link href="/" className="brand authbrand"><SaduMark size={30} />حداء</Link>
      <form className="card" onSubmit={submit}>
        <h1>تواصل مع دعم حداء</h1>
        <p className="small muted">اكتب سؤالك أو المشكلة، ونرد عليك خلال يوم عمل.</p>
        <label className="f" htmlFor="name">الاسم<input id="name" name="name" type="text" required maxLength={120} defaultValue={me?.user.fullName} key={me?.user.id} /></label>
        {!me && <label className="f" htmlFor="office">اسم المكتب (اختياري)<input id="office" name="office" type="text" maxLength={200} /></label>}
        <label className="f" htmlFor="email">البريد الإلكتروني<input id="email" name="email" type="email" dir="ltr" maxLength={200} defaultValue={me?.user.email} key={`e${me?.user.id}`} /></label>
        <label className="f" htmlFor="phone">الجوال <span className="small muted">البريد أو الجوال مطلوب</span><input id="phone" name="phone" type="tel" dir="ltr" placeholder="05xxxxxxxx" maxLength={30} /></label>
        <label className="f" htmlFor="topic">الموضوع
          <select id="topic" name="topic">{topics.map((t) => <option key={t}>{t}</option>)}</select></label>
        <label className="f" htmlFor="message">الرسالة<textarea id="message" name="message" rows={5} required minLength={10} maxLength={4000} /></label>
        {/* Hidden from people; bots fill it in. */}
        <input type="text" name="website" tabIndex={-1} autoComplete="off" aria-hidden="true" style={{ position: "absolute", left: "-9999px", width: 1, height: 1 }} />
        {error && <p className="err" role="alert">{error}</p>}
        <button className="btn primary" disabled={busy}>{busy ? "جارٍ الإرسال…" : "إرسال"}</button>
        <p className="small muted"><Link href={me ? "/dashboard" : "/login"}>رجوع</Link></p>
      </form>
    </main>
  );
}
