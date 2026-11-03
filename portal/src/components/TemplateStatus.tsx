"use client";

export type TemplateRow = { kind: "HANDOFF" | "REMINDER" | "FOLLOWUP" | "REPORT" | "NEW_LISTING" | "CHECKIN"; name: string; status: string; reason?: string | null; updatedAt: string };

const KINDS: Record<TemplateRow["kind"], string> = {
  HANDOFF: "تنبيه المسوّق بعميل جديد",
  REMINDER: "تذكير العميل بموعد المعاينة",
  FOLLOWUP: "سؤال العميل بعد المعاينة",
  REPORT: "التقرير اليومي على جوالك",
  NEW_LISTING: "عقار جديد يناسب طلب عميل سابق (تسويقي)",
  CHECKIN: "متابعة عميل توقف عن الرد (تسويقي)",
};

const STATUS: Record<string, [string, string]> = {
  APPROVED: ["معتمد", "p-ok"], PENDING: ["قيد مراجعة Meta", "p-warn"], IN_APPEAL: ["قيد الاعتراض", "p-warn"],
  PAUSED: ["موقوف مؤقتاً", "p-warn"], REJECTED: ["مرفوض", "p-bad"], DISABLED: ["معطّل", "p-bad"], ERROR: ["تعذّر الإنشاء", "p-bad"],
};

/** Meta's review of the office's message templates, used for messages outside WhatsApp's 24-hour window. */
export function TemplateStatus({ rows, busy, onSync }: { rows: TemplateRow[]; busy: boolean; onSync: () => void }) {
  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <h2>قوالب الرسائل</h2>
      <p className="small muted">واتساب لا يسمح بالرسائل خارج 24 ساعة من آخر رسالة للعميل إلا بقوالب تعتمدها Meta. ننشئها على رقمك تلقائياً، والمراجعة تأخذ عادة دقائق.</p>
      {rows.length === 0 ? <p className="small muted">لم تُنشأ بعد. اضغط «تحديث» لإنشائها.</p> : (
        <ul style={{ margin: 0, paddingInlineStart: 0, listStyle: "none", display: "flex", flexDirection: "column", gap: 6 }}>
          {rows.map((r) => {
            const [label, cls] = STATUS[r.status] ?? [r.status, "p-idle"];
            return (
              <li key={r.kind} className="row" style={{ justifyContent: "space-between" }}>
                <span>{KINDS[r.kind] ?? r.kind}</span>
                <span className={`pill ${cls}`} title={r.reason ?? undefined}>{label}</span>
              </li>
            );
          })}
        </ul>
      )}
      {rows.some((r) => r.reason) && <p className="small muted">{rows.filter((r) => r.reason).map((r) => `${KINDS[r.kind] ?? r.kind}: ${r.reason}`).join(" · ")}</p>}
      <div><button type="button" className="btn" disabled={busy} onClick={onSync}>{busy ? "جارٍ التحديث…" : "تحديث"}</button></div>
    </div>
  );
}
