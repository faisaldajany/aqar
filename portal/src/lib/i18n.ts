/**
 * The office app in Arabic or English. The choice lives in a cookie so the server renders the page in that language
 * (and direction) from the first byte; the website's English pages set it too.
 */
export type Lang = "ar" | "en";

export const LANG_COOKIE = "hidaa_lang";

export function parseLang(v: string | null | undefined): Lang {
  return v === "en" ? "en" : "ar";
}

export const dirOf = (l: Lang): "ltr" | "rtl" => (l === "en" ? "ltr" : "rtl");

/** Dates and numbers: Gregorian calendar, Latin digits, in either language. */
export const localeOf = (l: Lang) => (l === "en" ? "en-GB" : "ar-SA-u-ca-gregory-nu-latn");

/** The language in the browser (from the cookie), for code outside React such as the API helper. */
export function browserLang(): Lang {
  if (typeof document === "undefined") return "ar";
  const m = document.cookie.match(new RegExp(`(?:^|; )${LANG_COOKIE}=([^;]*)`));
  return parseLang(m?.[1]);
}

/** Remembers the language for a year. */
export function rememberLang(l: Lang) {
  document.cookie = `${LANG_COOKIE}=${l}; path=/; max-age=31536000; samesite=lax`;
}
