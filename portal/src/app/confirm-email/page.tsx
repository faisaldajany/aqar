"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef, useState } from "react";
import { Logo } from "@/components/Logo";
import { LangToggle, useLang } from "@/components/LangProvider";
import { api, ApiError } from "@/lib/api";

export default function ConfirmEmailPage() {
  return <Suspense fallback={null}><Confirm /></Suspense>;
}

/** The link from the verification email (the email also has a 6-digit code for /verify-email). */
function Confirm() {
  const { t } = useLang();
  const token = useSearchParams().get("token") ?? "";
  const [state, setState] = useState<"working" | "done" | "error">(token ? "working" : "error");
  const [error, setError] = useState(token ? "" : t("الرابط ناقص. افتحه كاملاً من رسالة البريد.", "The link is incomplete. Open the full link from the email."));
  const sent = useRef(false);

  useEffect(() => {
    if (!token || sent.current) return;
    sent.current = true;
    api("/auth/confirm-email", { method: "POST", json: { token } })
      .then(() => setState("done"))
      .catch((e) => { setState("error"); setError(e instanceof ApiError ? e.message : t("تعذّر التأكيد. حاول مرة أخرى.", "Couldn't confirm. Please try again.")); });
  }, [token, t]);

  return (
    <main className="auth">
      <div className="auth-lang"><LangToggle /></div>
      <Link href="/" className="brand authbrand"><Logo size={30} /></Link>
      <div className="card" style={{ maxWidth: 420, display: "flex", flexDirection: "column", gap: 12 }}>
        {state === "working" && <p className="muted">{t("جارٍ تأكيد البريد…", "Confirming your email…")}</p>}
        {state === "done" && (
          <>
            <h1>{t("تم تأكيد بريدك", "Your email is confirmed")}</h1>
            <p>{t("شكراً. يمكنك الآن متابعة تجهيز وكيلك.", "Thank you. You can now carry on setting up your agent.")}</p>
            <Link className="btn primary" href="/dashboard">{t("ادخل إلى حداء", "Go to Hidaa")}</Link>
          </>
        )}
        {state === "error" && (
          <>
            <h1>{t("لم يتم التأكيد", "Not confirmed")}</h1>
            <p>{error}</p>
            <Link className="btn" href="/verify-email">{t("أدخل الرمز أو اطلب رسالة جديدة", "Enter the code or ask for a new email")}</Link>
          </>
        )}
      </div>
    </main>
  );
}
