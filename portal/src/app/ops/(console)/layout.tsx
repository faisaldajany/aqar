"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Icon } from "@/components/Icon";
import { Logo } from "@/components/Logo";
import { api, ApiError } from "@/lib/api";
import { OpsContext } from "./context";

const NAV = [
  { href: "/ops", label: "المكاتب", icon: "building" },
  { href: "/ops/promos", label: "أكواد الخصم", icon: "card" },
  { href: "/ops/support", label: "رسائل الدعم", icon: "chat" },
  { href: "/ops/audit", label: "سجل العمليات", icon: "check" },
];


export default function OpsLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const path = usePathname();
  const [state, setState] = useState<{ mailConfigured: boolean } | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api<{ ok: boolean; mailConfigured: boolean }>("/ops/session")
      .then((s) => setState({ mailConfigured: s.mailConfigured }))
      .catch((e) => {
        if (e instanceof ApiError && (e.status === 404 || e.status === 401)) router.replace("/ops/login");
        else setError("تعذّر الاتصال بالخادم.");
      });
  }, [router]);

  async function logout() {
    await api("/ops/logout", { method: "POST" }).catch(() => undefined);
    router.replace("/ops/login");
  }

  if (!state) return <main className="auth"><p className="muted">{error || "جارٍ التحميل…"}</p></main>;

  return (
    <OpsContext.Provider value={state}>
      <div className="shell">
        <aside className="side">
          <Link href="/ops" className="brand"><Logo size={26} /></Link>
          <div className="office"><b>لوحة الإدارة</b><span className="pill p-warn">فريق حداء فقط</span></div>
          <nav aria-label="أقسام الإدارة">
            {NAV.map((n) => {
              const current = n.href === "/ops" ? path === "/ops" || path.startsWith("/ops/offices") : path.startsWith(n.href);
              return <Link key={n.href} href={n.href} aria-current={current ? "page" : undefined}><Icon name={n.icon} />{n.label}</Link>;
            })}
          </nav>
          <div className="sidefoot">
            <button type="button" onClick={logout}><Icon name="out" />خروج</button>
          </div>
        </aside>
        <main className="main"><div className="view">{children}</div></main>
      </div>
    </OpsContext.Provider>
  );
}
