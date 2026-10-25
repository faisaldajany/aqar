"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, type Alert, type Stats, type Usage } from "@/lib/api";
import { useSession } from "../session";

export default function Dashboard() {
  const { me } = useSession();
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [usage, setUsage] = useState<Usage | null>(null);
  const [stats, setStats] = useState<Stats | null>(null);

  useEffect(() => {
    api<Alert[]>("/alerts?unread=true").then(setAlerts).catch(() => undefined);
    api<Usage>("/usage").then(setUsage).catch(() => undefined);
    api<Stats>("/stats").then(setStats).catch(() => undefined);
  }, []);

  async function dismiss(id: string) {
    await api(`/alerts/${id}/read`, { method: "POST" });
    setAlerts((a) => a.filter((x) => x.id !== id));
  }

  return (
    <>
      <h1>أهلاً {me.user.fullName}</h1>
      {stats && (
        <div className="kpis" style={{ marginBottom: 12 }}>
          <div className="kpi"><b>{stats.inquiriesToday}</b><span>استفسار اليوم ({stats.inquiriesWeek} هذا الأسبوع)</span></div>
          <div className="kpi"><b>{stats.qualifiedWeek}</b><span>عميل اكتمل طلبه هذا الأسبوع</span></div>
          <div className="kpi"><b>{stats.bookingsToday}</b><span>موعد اليوم ({stats.bookedWeek} حُجز هذا الأسبوع)</span></div>
          <div className="kpi"><b>{stats.openHandoffs}</b><span><Link href="/handoffs">تحويل بانتظار مسوّق</Link></span></div>
          <div className="kpi"><b>{stats.afterHoursWeek}</b><span>رسالة بعد الدوام (10م–8ص) هذا الأسبوع</span></div>
          <div className="kpi"><b>{stats.medianReplyMs == null ? "—" : `${(stats.medianReplyMs / 1000).toFixed(1)}ث`}</b><span>متوسط سرعة الرد</span></div>
        </div>
      )}
      <div className="grid2">
        <div className="card">
          <h2>رقم واتساب المكتب</h2>
          {me.whatsapp.connected ? (
            <p><span className="pill p-ok">مربوط</span> <span className="mono">{me.whatsapp.displayPhone}</span></p>
          ) : (
            <p className="muted">لم يُربط بعد، والوكيل لا يعمل قبل ربطه. <Link href="/whatsapp">اربط الرقم</Link></p>
          )}
        </div>
        {usage && (
          <div className="card">
            <h2>باقة {usage.planName}</h2>
            <p className="small">{usage.replies.toLocaleString("en-US")} من {usage.included.toLocaleString("en-US")} رد آلي هذا الشهر</p>
            <div className="meter" role="progressbar" aria-valuenow={usage.percent} aria-valuemin={0} aria-valuemax={100}>
              <i style={{ width: `${Math.min(usage.percent, 100)}%` }} />
            </div>
            {usage.overageReplies > 0 && <p className="small" style={{ color: "var(--warn)" }}>ردود إضافية: {usage.overageReplies} ({usage.overageSar} ريال)</p>}
          </div>
        )}
      </div>
      <div className="card">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2>يحتاج انتباهك</h2>
          {alerts.length > 0 && <button className="btn" onClick={async () => { await api("/alerts/read-all", { method: "POST" }); setAlerts([]); }}>تعليم الكل كمقروء</button>}
        </div>
        {alerts.length === 0 ? <p className="small muted">لا تنبيهات جديدة.</p> : (
          <ul style={{ listStyle: "none", padding: 0, margin: 0, display: "flex", flexDirection: "column", gap: 8 }}>
            {alerts.map((a) => (
              <li key={a.id} className="row" style={{ justifyContent: "space-between", borderBottom: "1px solid var(--line)", paddingBottom: 8 }}>
                <span className="small">{a.message}</span>
                <span className="row">
                  {a.kind.startsWith("LICENSE") && <Link className="btn" href="/listings">العقارات</Link>}
                  <button className="btn" onClick={() => dismiss(a.id)}>تم</button>
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
}
