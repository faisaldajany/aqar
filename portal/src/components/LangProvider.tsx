"use client";

import { createContext, useCallback, useContext } from "react";
import { dirOf, localeOf, rememberLang, type Lang } from "@/lib/i18n";

const LangContext = createContext<Lang>("ar");

/** Set once in the root layout from the language cookie. */
export function LangProvider({ lang, children }: { lang: Lang; children: React.ReactNode }) {
  return <LangContext.Provider value={lang}>{children}</LangContext.Provider>;
}

/**
 * The page's language. {@code t(ar, en)} picks the text for it, keeping both versions next to each other in the code;
 * it works for strings and for JSX.
 */
export function useLang() {
  const lang = useContext(LangContext);
  const t = useCallback(<T,>(ar: T, en: T): T => (lang === "en" ? en : ar), [lang]);
  return { lang, en: lang === "en", t, dir: dirOf(lang), locale: localeOf(lang) };
}

/** Switches the language and reloads, so the whole page (and its direction) changes. */
export function switchLang(to: Lang) {
  rememberLang(to);
  window.location.reload();
}

/** The «English» / «العربية» button. */
export function LangToggle({ className = "linkbtn small" }: { className?: string }) {
  const { lang } = useLang();
  const to: Lang = lang === "en" ? "ar" : "en";
  return (
    <button type="button" className={className} lang={to} onClick={() => switchLang(to)}>
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true" style={{ verticalAlign: "-2px", marginInlineEnd: 4 }}>
        <circle cx="12" cy="12" r="10" /><path d="M2 12h20M12 2a15 15 0 0 1 0 20M12 2a15 15 0 0 0 0 20" />
      </svg>
      {to === "en" ? "English" : "العربية"}
    </button>
  );
}
