"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Logo } from "@/components/Logo";
import { PartnerView } from "@/components/PartnerView";
import { api, ApiError } from "@/lib/api";
import type { PartnerPage } from "@/lib/partner";

/** The marketer's own page: what they earned, from which clients, and what was paid out. */
export default function PartnerHome() {
  const router = useRouter();
  const [page, setPage] = useState<PartnerPage | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api<PartnerPage>("/partner/me").then(setPage).catch((e) => {
      if (e instanceof ApiError && e.status === 401) router.replace("/partner/login");
      else setError("تعذّر التحميل. حدّث الصفحة.");
    });
  }, [router]);

  async function logout() {
    await api("/partner/logout", { method: "POST" }).catch(() => undefined);
    router.replace("/partner/login");
  }

  if (!page) return <main className="auth"><p className="muted">{error || "جارٍ التحميل…"}</p></main>;

  return (
    <div className="partner">
      <header className="partner-top">
        <Link href="/" aria-label="حداء"><Logo size={26} /></Link>
        <span className="pill p-acc">بوابة المسوّقين</span>
        <span className="grow" />
        <span className="small muted">{page.affiliate.fullName}</span>
        <button type="button" className="btn" onClick={() => void logout()}>خروج</button>
      </header>
      <main className="view">
        <h1>أهلاً {page.affiliate.fullName.split(" ")[0]}</h1>
        <PartnerView page={page} />
        <p className="small muted">للاستفسار عن عمولتك أو التحويلات: <a href="mailto:support@hidaaco.com" dir="ltr">support@hidaaco.com</a></p>
      </main>
    </div>
  );
}
