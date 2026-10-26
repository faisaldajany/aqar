"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type Me } from "@/lib/api";
import { SessionContext } from "./session";
import { Icon } from "@/components/Icon";
import { Logo } from "@/components/Logo";

const NAV: { href: string; label: string; icon: string; group: "work" | "setup" }[] = [
  { href: "/dashboard", label: "الرئيسية", icon: "home", group: "work" },
  { href: "/inbox", label: "المحادثات", icon: "chat", group: "work" },
  { href: "/handoffs", label: "التحويلات", icon: "handoff", group: "work" },
  { href: "/bookings", label: "المواعيد", icon: "calendar", group: "work" },
  { href: "/try", label: "جرّب الوكيل", icon: "try", group: "work" },
  { href: "/listings", label: "العقارات", icon: "building", group: "setup" },
  { href: "/marketers", label: "المسوّقون", icon: "people", group: "setup" },
  { href: "/whatsapp", label: "ربط واتساب", icon: "phone", group: "setup" },
  { href: "/billing", label: "الاشتراك", icon: "card", group: "setup" },
  { href: "/settings", label: "الإعدادات", icon: "gear", group: "setup" },
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
          <Link href="/dashboard" className="brand"><Logo size={26} /></Link>
          <div className="office">
            <b>{me.office.name}</b>
            {me.whatsapp.connected && me.whatsapp.verifiedName === "مكتب تجريبي"
              ? <span className="pill p-warn">واتساب تجريبي</span>
              : me.whatsapp.connected ? <span className="pill p-ok">واتساب مربوط</span> : <span className="pill p-idle">واتساب غير مربوط</span>}
          </div>
          <nav aria-label="أقسام البوابة">
            {NAV.filter((n) => n.group === "work").map((n) => (
              <Link key={n.href} href={n.href} aria-current={path === n.href ? "page" : undefined}><Icon name={n.icon} />{n.label}</Link>
            ))}
            <span className="navsep" role="separator" />
            {NAV.filter((n) => n.group === "setup").map((n) => (
              <Link key={n.href} href={n.href} aria-current={path === n.href ? "page" : undefined}><Icon name={n.icon} />{n.label}</Link>
            ))}
          </nav>
          <div className="sidefoot">
            <Link href="/contact"><Icon name="help" />الدعم</Link>
            <button type="button" onClick={logout}><Icon name="out" />خروج</button>
          </div>
        </aside>
        <main className="main"><div className="view">{children}</div></main>
      </div>
    </SessionContext.Provider>
  );
}
