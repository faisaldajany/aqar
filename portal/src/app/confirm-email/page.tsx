"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef, useState } from "react";
import { Logo } from "@/components/Logo";
import { api, ApiError } from "@/lib/api";

export default function ConfirmEmailPage() {
  return <Suspense fallback={null}><Confirm /></Suspense>;
}

/** The link from the verification email (the email also has a 6-digit code for /verify-email). */
function Confirm() {
  const token = useSearchParams().get("token") ?? "";
  const [state, setState] = useState<"working" | "done" | "error">(token ? "working" : "error");
  const [error, setError] = useState(token ? "" : "الرابط ناقص. افتحه كاملاً من رسالة البريد.");
  const sent = useRef(false);

  useEffect(() => {
    if (!token || sent.current) return;
    sent.current = true;
    api("/auth/confirm-email", { method: "POST", json: { token } })
      .then(() => setState("done"))
      .catch((e) => { setState("error"); setError(e instanceof ApiError ? e.message : "تعذّر التأكيد. حاول مرة أخرى."); });
  }, [token]);

  return (
    <main className="auth">
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
        {state === "working" && <p className="muted">جارٍ تأكيد البريد…</p>}
        {state === "done" && (
          <>
            <h1>تم تأكيد بريدك</h1>
            <p>شكراً. يمكنك الآن متابعة تجهيز وكيلك.</p>
            <Link className="btn primary" href="/dashboard">ادخل إلى حداء</Link>
          </>
        )}
        {state === "error" && (
          <>
            <h1>لم يتم التأكيد</h1>
            <p>{error}</p>
            <Link className="btn" href="/verify-email">أدخل الرمز أو اطلب رسالة جديدة</Link>
          </>
        )}
      </div>
    </main>
  );
}
