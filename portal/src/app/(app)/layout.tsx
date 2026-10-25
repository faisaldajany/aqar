"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type Me } from "@/lib/api";
import { SessionContext } from "./session";

const NAV = [
  { href: "/dashboard", label: "الرئيسية" },
  { href: "/inbox", label: "المحادثات" },
  { href: "/handoffs", label: "التحويلات" },
  { href: "/bookings", label: "المواعيد" },
  { href: "/listings", label: "العقارات" },
  { href: "/marketers", label: "المسوّقون" },
  { href: "/whatsapp", label: "ربط واتساب" },
  { href: "/billing", label: "الاشتراك" },
  { href: "/settings", label: "الإعدادات" },
];

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const path = usePathname();
  const [me, setMe] = useState<Me | null>(null);
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    try {
      setMe(await api<Me>("/me"));
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) router.replace("/login");
      else setError("تعذّر الاتصال بالخادم.");
    }
  }, [router]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  async function logout() {
    await api("/auth/logout", { method: "POST" }).catch(() => undefined);
    router.replace("/login");
  }

  if (!me) {
    return <main className="auth"><p className="muted">{error || "جارٍ التحميل…"}</p></main>;
  }

  return (
    <SessionContext.Provider value={{ me, refresh }}>
      <div className="shell">
        <aside className="side">
          <div className="brand">وصلة</div>
          <div className="small"><b>{me.office.name}</b><br /><span className="muted">فال <span className="mono">{me.office.falLicense}</span></span></div>
          <nav aria-label="أقسام البوابة">
            {NAV.map((n) => (
              <Link key={n.href} href={n.href} aria-current={path === n.href ? "page" : undefined}>{n.label}</Link>
            ))}
          </nav>
          <button className="btn" onClick={logout} style={{ marginTop: "auto" }}>تسجيل الخروج</button>
        </aside>
        <main className="main"><div className="view">{children}</div></main>
      </div>
    </SessionContext.Provider>
  );
}
