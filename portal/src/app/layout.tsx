import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "حداء",
  description: "بوابة مكاتب العقار لإدارة وكيل واتساب",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ar" dir="rtl">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="" />
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+Arabic:wght@400;600;700&family=Reem+Kufi:wght@500;700&display=swap"
        />
      </head>
      <body>{children}</body>
    </html>
  );
}
