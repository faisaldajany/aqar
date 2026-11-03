"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { ACTION_LABELS, when, type OpsAudit } from "@/lib/ops";

export default function OpsAuditPage() {
  const [rows, setRows] = useState<OpsAudit[] | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api<OpsAudit[]>("/ops/audit").then(setRows).catch(() => setError("تعذّر التحميل."));
  }, []);

  if (!rows) return <p className="muted">{error || "جارٍ التحميل…"}</p>;
  return (
    <>
      <h1>سجل العمليات</h1>
      <p className="small muted">كل تغيير من لوحة الإدارة، آخر 200 عملية.</p>
      <div className="tbl"><table>
        <thead><tr><th>الوقت</th><th>العملية</th><th>المكتب</th><th>التفاصيل</th></tr></thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={4} className="muted">لا عمليات بعد.</td></tr>}
          {rows.map((a, n) => (
            <tr key={n}>
              <td className="small">{when(a.at)}</td>
              <td>{ACTION_LABELS[a.action] ?? a.action}</td>
              <td>{a.officeId ? <Link href={`/ops/offices/${a.officeId}`}>فتح</Link> : "—"}</td>
              <td className="small">{[a.target, a.detail].filter(Boolean).join(" · ") || "—"}</td>
            </tr>
          ))}
        </tbody>
      </table></div>
    </>
  );
}
