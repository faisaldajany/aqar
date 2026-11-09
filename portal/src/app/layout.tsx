import type { Metadata } from "next";
import { cookies } from "next/headers";
import { LangProvider } from "@/components/LangProvider";
import { dirOf, LANG_COOKIE, parseLang } from "@/lib/i18n";
import "./globals.css";

export const metadata: Metadata = {
  title: "حداء",
  description: "وكيل آلي على رقم واتساب مكتبك العقاري: يرد خلال ثوانٍ، ويعرض عقاراتك المرخّصة، ويحجز المعاينة.",
  metadataBase: new URL("https://hidaaco.com"),
  openGraph: { title: "حداء — وكيل لمكاتب العقار", locale: "ar_SA", type: "website" },
};

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  // Arabic unless the visitor chose English (the cookie), so the first render has the right language and direction.
  const lang = parseLang((await cookies()).get(LANG_COOKIE)?.value);
  return (
    <html lang={lang} dir={dirOf(lang)}>
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="" />
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+Arabic:wght@400;600;700&family=Noto+Kufi+Arabic:wght@600;700&display=swap"
        />
      </head>
      <body><LangProvider lang={lang}>{children}</LangProvider></body>
    </html>
  );
}
