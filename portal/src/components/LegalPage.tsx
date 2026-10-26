import Link from "next/link";
import { Logo } from "./Logo";

export function LegalPage({ title, updated, children }: { title: string; updated: string; children: React.ReactNode }) {
  return (
    <div className="legal">
      <header><Link href="/" aria-label="حداء، الصفحة الرئيسية"><Logo size={28} /></Link></header>
      <main>
        <h1>{title}</h1>
        <p className="muted small">آخر تحديث: {updated}</p>
        {children}
        <p className="muted small">للاستفسار: <a href="mailto:faisal@hidaaco.com">faisal@hidaaco.com</a> أو <Link href="/contact">نموذج التواصل</Link>.</p>
      </main>
    </div>
  );
}
