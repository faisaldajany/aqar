import Link from "next/link";
import { CompanyLine } from "./CompanyLine";
import { LangSwitch } from "./LangSwitch";
import { Logo } from "./Logo";

/**
 * The privacy, terms and refund pages, in Arabic or English. {@code other} is the same page in the other language,
 * linked from the switch at the top.
 */
export function LegalPage({ title, updated, children, lang = "ar", other }: {
  title: string; updated: string; children: React.ReactNode; lang?: "ar" | "en"; other?: string;
}) {
  const en = lang === "en";
  return (
    <>
      {other && <LangSwitch to={other} lang={en ? "ar" : "en"} />}
      <div className="legal" lang={lang} dir={en ? "ltr" : "rtl"}>
        <header><Link href={en ? "/en" : "/"} aria-label={en ? "Hidaa, home" : "حداء، الصفحة الرئيسية"}><Logo size={28} lang={lang} /></Link></header>
        <main>
          <h1>{title}</h1>
          <p className="muted small">{en ? "Last updated: " : "آخر تحديث: "}{updated}</p>
          {children}
          {en ? (
            <p className="muted small">Questions: <a href="mailto:support@hidaaco.com">support@hidaaco.com</a> or the <Link href="/contact">contact form</Link>.</p>
          ) : (
            <p className="muted small">للاستفسار: <a href="mailto:support@hidaaco.com" dir="ltr">support@hidaaco.com</a> أو <Link href="/contact">نموذج التواصل</Link>.</p>
          )}
        </main>
        <footer><CompanyLine className="muted small" lang={lang} /></footer>
      </div>
    </>
  );
}
