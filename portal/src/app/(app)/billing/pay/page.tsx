"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { api, ApiError } from "@/lib/api";

/** Development stand-in for Moyasar's hosted page (only works when the API runs with DEV_TOOLS=true). */
export default function MockPayPage() {
  return <Suspense fallback={null}><MockPay /></Suspense>;
}

function MockPay() {
  const id = useSearchParams().get("invoice");
  const router = useRouter();
  const [error, setError] = useState("");

  async function pay() {
    try {
      await api(`/billing/invoices/${id}/mock-pay`, { method: "POST" });
      router.push(`/billing?invoice=${id}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر الدفع التجريبي.");
    }
  }

  return (
    <div className="card" style={{ maxWidth: 420 }}>
      <h1>دفع تجريبي</h1>
      <p className="small muted">هذه الصفحة بديل صفحة ميسّر في بيئة التطوير فقط. في الإنتاج تنتقل لصفحة الدفع الحقيقية.</p>
      <div className="row"><button className="btn primary" onClick={pay}>ادفع (تجريبي)</button><button className="btn" onClick={() => router.push("/billing")}>رجوع</button></div>
      {error && <p className="err">{error}</p>}
    </div>
  );
}
