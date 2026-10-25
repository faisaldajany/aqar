"use client";

import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useSession } from "../session";

type Config = { mode: "mock" | "cloud"; appId: string; configId: string; graphVersion: string };

type SignupInfo = { wabaId?: string; phoneNumberId?: string; coexistence: boolean };

declare global {
  interface Window {
    FB?: {
      init: (o: Record<string, unknown>) => void;
      login: (cb: (r: { authResponse?: { code?: string } }) => void, o: Record<string, unknown>) => void;
    };
    fbAsyncInit?: () => void;
  }
}

/**
 * Meta Embedded Signup with WhatsApp Business app coexistence. The SDK reports the WABA and phone
 * number ids through a postMessage event and the auth code through FB.login's callback; both are
 * sent to the backend, which finishes the exchange. See docs/meta-integration.md.
 */
export default function WhatsAppPage() {
  const { me, refresh } = useSession();
  const [config, setConfig] = useState<Config | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const info = useRef<SignupInfo>({ coexistence: true });

  useEffect(() => {
    api<Config>("/whatsapp/config").then(setConfig).catch(() => setError("تعذّر تحميل إعدادات الربط."));
  }, []);

  useEffect(() => {
    if (config?.mode !== "cloud" || !config.appId) return;
    window.fbAsyncInit = () => window.FB?.init({ appId: config.appId, autoLogAppEvents: true, xfbml: true, version: config.graphVersion });
    if (!document.getElementById("facebook-jssdk")) {
      const s = document.createElement("script");
      s.id = "facebook-jssdk";
      s.src = "https://connect.facebook.net/en_US/sdk.js";
      s.async = true;
      s.crossOrigin = "anonymous";
      document.body.appendChild(s);
    }
    const onMessage = (event: MessageEvent) => {
      if (!/(^|\.)facebook\.com$/.test(new URL(event.origin).hostname)) return;
      try {
        const data = typeof event.data === "string" ? JSON.parse(event.data) : event.data;
        if (data?.type !== "WA_EMBEDDED_SIGNUP") return;
        if (data.event === "FINISH" || data.event === "FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING") {
          info.current = {
            wabaId: data.data?.waba_id,
            phoneNumberId: data.data?.phone_number_id,
            coexistence: data.event === "FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING",
          };
        } else if (data.event === "CANCEL") {
          setError("أُغلقت نافذة Meta قبل اكتمال الربط.");
        }
      } catch {
        /* not a signup message */
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [config]);

  async function finish(code: string | null) {
    setBusy(true);
    setError("");
    try {
      await api("/whatsapp/connect", { method: "POST", json: { code, ...info.current } });
      await refresh();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر إكمال الربط.");
    } finally {
      setBusy(false);
    }
  }

  function connectWithMeta() {
    if (!window.FB || !config) {
      setError("لم تُحمّل نافذة Meta بعد. انتظر لحظة ثم أعد المحاولة.");
      return;
    }
    window.FB.login(
      (r) => {
        if (r.authResponse?.code) void finish(r.authResponse.code);
        else setError("لم تكتمل نافذة Meta.");
      },
      {
        config_id: config.configId,
        response_type: "code",
        override_default_response_type: true,
        extras: { setup: {}, featureType: "whatsapp_business_app_onboarding", sessionInfoVersion: "3" },
      },
    );
  }

  if (me.whatsapp.connected) {
    return (
      <div className="card">
        <h1>رقم واتساب مربوط</h1>
        <p><span className="mono">{me.whatsapp.displayPhone}</span> · {me.whatsapp.verifiedName}</p>
        <p className="small muted">يبقى المسوّقون يردون من تطبيق واتساب للأعمال، ويتوقف الوكيل في أي محادثة يرد فيها أحدهم.</p>
      </div>
    );
  }

  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <h1>اربط رقم واتساب المكتب</h1>
      <p className="muted">يعمل الوكيل على رقمكم وتطبيق واتساب للأعمال نفسيهما. قبل البدء:</p>
      <ul className="small">
        <li>أن يكون الرقم على تطبيق <b>واتساب للأعمال</b> منذ 7 أيام على الأقل.</li>
        <li>ألا يكون مربوطاً بمنصة أو روبوت آخر.</li>
        <li>أن يكون الجوال معك لمسح رمز QR من التطبيق.</li>
      </ul>
      {config?.mode === "mock" ? (
        <div className="row">
          <button className="btn primary" disabled={busy} onClick={() => finish(null)}>ربط تجريبي</button>
          <span className="small muted">وضع التطوير: لا اتصال بـ Meta.</span>
        </div>
      ) : (
        <button className="btn primary" disabled={busy || !config} onClick={connectWithMeta}>اربط عبر Meta</button>
      )}
      {error && <p className="err" role="alert">{error}</p>}
    </div>
  );
}
