"use client";

import { rememberLang } from "@/lib/i18n";

/**
 * The Arabic/English switch at the top of the public pages: a slim bar above the page, linking to the same page in
 * the other language. It also remembers the choice, so the office app opens in that language.
 */
export function LangSwitch({ to, lang }: { to: string; lang: "ar" | "en" }) {
  return (
    <div className="lang-bar" dir={lang === "en" ? "rtl" : "ltr"}>
      <a href={to} hrefLang={lang} lang={lang} onClick={() => rememberLang(lang)}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <circle cx="12" cy="12" r="10" /><path d="M2 12h20M12 2a15 15 0 0 1 0 20M12 2a15 15 0 0 0 0 20" />
        </svg>
        {lang === "en" ? "English" : "العربية"}
      </a>
    </div>
  );
}
