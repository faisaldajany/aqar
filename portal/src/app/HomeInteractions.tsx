"use client";

import { useEffect } from "react";

const TEXT = {
  ar: {
    incomplete: "اكتب اسمك وجوالك أو بريدك ورسالة من 10 أحرف على الأقل.",
    sending: "جارٍ الإرسال…",
    topic: "سؤال عن الخدمة",
    failed: "تعذّر الإرسال. حاول مرة أخرى.",
    sent: "وصلتنا رسالتك، ونرد عليك خلال يوم عمل.",
    offline: "تعذّر الإرسال. تحقق من الاتصال وحاول مرة أخرى.",
  },
  en: {
    incomplete: "Write your name, your mobile or email, and a message of at least 10 characters.",
    sending: "Sending…",
    topic: "سؤال عن الخدمة", // the support inbox's own category (Arabic either way)
    failed: "Couldn't send. Please try again.",
    sent: "We got your message and will reply within one working day.",
    offline: "Couldn't send. Check your connection and try again.",
  },
};

/** Sale/rent switch in the hero chat and the FAQ accordion, for the static home page markup (Arabic or English). */
export function HomeInteractions({ lang = "ar" }: { lang?: "ar" | "en" }) {
  useEffect(() => {
    const t = TEXT[lang];
    const root = document.getElementById("home");
    if (!root) return;
    const cleanups: (() => void)[] = [];

    // FAQ: first answer open, the rest closed; one open at a time.
    const buttons = Array.from(root.querySelectorAll<HTMLButtonElement>("button[data-faq]"));
    const setOpen = (open: number) => {
      buttons.forEach((b) => {
        const i = Number(b.dataset.faq);
        const isOpen = i === open;
        b.setAttribute("aria-expanded", String(isOpen));
        const chev = b.querySelector("svg");
        if (chev) chev.style.transform = isOpen ? "rotate(180deg)" : "none";
        root.querySelectorAll<HTMLElement>(`[data-faq-answer="${i}"]`).forEach((a) => { a.hidden = !isOpen; });
      });
    };
    let current = 0;
    setOpen(current);
    buttons.forEach((b) => {
      const onClick = () => { const i = Number(b.dataset.faq); current = current === i ? -1 : i; setOpen(current); };
      b.addEventListener("click", onClick);
      cleanups.push(() => b.removeEventListener("click", onClick));
    });

    // Sale / rent example in the hero phone.
    const sale = root.querySelector<HTMLButtonElement>('button[data-mode="sale"]');
    const rent = root.querySelector<HTMLButtonElement>('button[data-mode="rent"]');
    if (sale && rent) {
      const on = sale.getAttribute("style") ?? "";
      const off = rent.getAttribute("style") ?? "";
      const pick = (mode: "sale" | "rent") => {
        root.querySelectorAll<HTMLElement>("[data-sale]").forEach((el) => { el.textContent = el.dataset[mode] ?? el.textContent; });
        sale.setAttribute("style", mode === "sale" ? on : off);
        rent.setAttribute("style", mode === "rent" ? on : off);
        sale.setAttribute("aria-pressed", String(mode === "sale"));
        rent.setAttribute("aria-pressed", String(mode === "rent"));
      };
      const s = () => pick("sale"), r = () => pick("rent");
      sale.addEventListener("click", s);
      rent.addEventListener("click", r);
      cleanups.push(() => { sale.removeEventListener("click", s); rent.removeEventListener("click", r); });
      pick("sale");
    }
    // "Talk to the owner" form: saved and forwarded to the owner's inbox by the support endpoint.
    const form = root.querySelector<HTMLFormElement>("#owner-form");
    if (form) {
      const status = form.querySelector<HTMLElement>("[data-form-status]");
      const say = (text: string, ok: boolean) => { if (status) { status.textContent = text; status.style.color = ok ? "#1F6B52" : "#A3192D"; } };
      const onSubmit = async (ev: Event) => {
        ev.preventDefault();
        const f = new FormData(form);
        const name = String(f.get("name") ?? "").trim();
        const contact = String(f.get("contact") ?? "").trim();
        const message = String(f.get("message") ?? "").trim();
        if (!name || !contact || message.length < 10) { say(t.incomplete, false); return; }
        const isEmail = contact.includes("@");
        const button = form.querySelector<HTMLButtonElement>("button[type=submit]");
        if (button) button.disabled = true;
        say(t.sending, true);
        try {
          const res = await fetch("/api/public/support", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              name, email: isEmail ? contact : null, phone: isEmail ? null : contact,
              topic: t.topic, message, source: "website", website: f.get("website"),
            }),
          });
          const data = await res.json().catch(() => ({}));
          // The server's messages are in Arabic: the English page shows its own.
          if (!res.ok) { say(lang === "en" ? t.failed : data.message ?? t.failed, false); return; }
          form.reset();
          say(t.sent, true);
        } catch {
          say(t.offline, false);
        } finally {
          if (button) button.disabled = false;
        }
      };
      form.addEventListener("submit", onSubmit);
      cleanups.push(() => form.removeEventListener("submit", onSubmit));
    }

    return () => cleanups.forEach((c) => c());
  }, [lang]);
  return null;
}
