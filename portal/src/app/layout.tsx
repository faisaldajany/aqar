import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "حداء",
  description: "وكيل آلي على رقم واتساب مكتبك العقاري: يرد خلال ثوانٍ، ويعرض عقاراتك المرخّصة، ويحجز المعاينة.",
  metadataBase: new URL("https://hidaaco.com"),
  openGraph: { title: "حداء — وكيل واتساب لمكاتب العقار", locale: "ar_SA", type: "website" },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ar" dir="rtl">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="" />
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+Arabic:wght@400;600;700&family=Noto+Kufi+Arabic:wght@600;700&display=swap"
        />
      </head>
      <body>{children}</body>
    </html>
  );
}
