"use client";

import Link from "next/link";
import { useSession } from "../session";

export default function Dashboard() {
  const { me } = useSession();
  return (
    <>
      <h1>أهلاً {me.user.fullName}</h1>
      <div className="card row" style={{ justifyContent: "space-between" }}>
        <div>
          <h2>رقم واتساب المكتب</h2>
          {me.whatsapp.connected ? (
            <p><span className="pill p-ok">مربوط</span> <span className="mono">{me.whatsapp.displayPhone}</span>
              {me.whatsapp.coexistence && <span className="small muted"> · على الرقم والتطبيق الحاليين</span>}</p>
          ) : (
            <p className="muted">لم يُربط بعد. الوكيل لا يعمل قبل ربط الرقم.</p>
          )}
        </div>
        {!me.whatsapp.connected && <Link className="btn primary" href="/whatsapp">اربط الرقم</Link>}
      </div>
      <div className="card">
        <h2>الخطوات القادمة</h2>
        <p className="small muted">رفع العقارات، والمسوّقون، وإعداد المواعيد، واختبار الوكيل تُضاف في المراحل التالية.</p>
      </div>
    </>
  );
}
