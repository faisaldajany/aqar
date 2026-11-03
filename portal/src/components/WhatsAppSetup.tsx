"use client";

import Link from "next/link";
import { FormEvent, ReactNode, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Icon } from "./Icon";

export type SetupStepKey = "number" | "app" | "registered" | "profile" | "facebook" | "active" | "connect" | "test";

export type WaSetup = {
  hasBusinessApp: boolean | null;
  phone: string | null;
  businessAppSince: string | null;
  readyOn: string | null;
  daysLeft: number;
  officeName: string;
  connected: boolean;
  steps: { key: SetupStepKey; label: string; done: boolean; doneAt: string | null; auto: boolean }[];
  current: SetupStepKey | null;
  done: number;
  total: number;
};

const LOCALE = "ar-SA-u-ca-gregory-nu-latn";
const day = (iso: string) => new Date(`${iso}T12:00:00`).toLocaleDateString(LOCALE, { weekday: "long", day: "numeric", month: "long" });
const today = () => new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Riyadh" });
const days = (n: number) => (n === 1 ? "يوم واحد" : n === 2 ? "يومان" : n <= 10 ? `${n} أيام` : `${n} يوماً`);

const APP_STORE = "https://apps.apple.com/app/whatsapp-business/id1386412706";
const PLAY_STORE = "https://play.google.com/store/apps/details?id=com.whatsapp.w4b";

/**
 * Step-by-step WhatsApp setup for an office without a WhatsApp Business account, up to the first customer message.
 * The owner confirms what they did on the phone; Hidaa checks the rest (7 days of use, the connection, the first
 * message). Progress is saved on the server, so it continues from any device.
 */
export function WhatsAppSetupGuide({ state, onChange, connect }: {
  state: WaSetup;
  onChange: (s: WaSetup) => void;
  /** The «اربط عبر Meta» button (or the test-mode one). */
  connect: ReactNode;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ key: string; text: string } | null>(null);
  const has = state.hasBusinessApp === true;

  async function call(key: string, path: string, init: RequestInit & { json?: unknown }) {
    setBusy(true);
    setError(null);
    try {
      onChange(await api<WaSetup>(path, init));
    } catch (e) {
      setError({ key, text: e instanceof ApiError ? e.message : "تعذّر الحفظ. حاول مرة أخرى." });
    } finally {
      setBusy(false);
    }
  }
  const confirm = (key: SetupStepKey, value?: string) =>
    call(key, `/whatsapp/setup/steps/${key}`, { method: "POST", json: value === undefined ? {} : { value } });
  const undo = (key: SetupStepKey) => call(key, `/whatsapp/setup/steps/${key}`, { method: "DELETE" });
  const choose = (v: boolean) => call("path", "/whatsapp/setup/path", { method: "POST", json: { hasBusinessApp: v } });

  if (state.hasBusinessApp === null && state.done === 0) {
    return (
      <section className="card wasetup" aria-labelledby="wa-h">
        <h1 id="wa-h">اربط رقم واتساب المكتب</h1>
        <p>يرد وكيل حداء على عملائك من رقم المكتب نفسه، وتبقى أنت ومسوّقوك تردّون من تطبيق واتساب للأعمال. نمشي معك خطوة بخطوة، وتؤكّد كل خطوة هنا.</p>
        <h2 className="q">هل رقم المكتب مسجّل الآن في تطبيق <b>واتساب للأعمال</b>؟</h2>
        <div className="row">
          <button className="btn primary" disabled={busy} onClick={() => choose(false)}>لا، ليس بعد</button>
          <button className="btn" disabled={busy} onClick={() => choose(true)}>نعم، مسجّل</button>
        </div>
        {error?.key === "path" && <p className="err" role="alert">{error.text}</p>}
      </section>
    );
  }

  const before = (key: SetupStepKey) => state.steps.slice(0, state.steps.findIndex((s) => s.key === key));
  const readyToConnect = before("connect").every((s) => s.done);

  function body(key: SetupStepKey): ReactNode {
    switch (key) {
      case "number":
        return <NumberStep busy={busy} has={has} onSave={(v) => confirm("number", v)} />;
      case "app":
        return (
          <>
            <p>{has ? "حدّث تطبيق واتساب للأعمال إلى آخر إصدار على الجوال الذي فيه رقم المكتب." : "ثبّت تطبيق واتساب للأعمال على الجوال الذي فيه شريحة رقم المكتب. هو تطبيق مستقل عن واتساب العادي ومجاني."}</p>
            <div className="row">
              <a className="btn" href={APP_STORE} target="_blank" rel="noopener noreferrer">App Store · آيفون</a>
              <a className="btn" href={PLAY_STORE} target="_blank" rel="noopener noreferrer">Google Play · أندرويد</a>
            </div>
            <p className="small muted">يلزم الإصدار 2.24.17 أو أحدث. تعرف إصدارك من التطبيق: الإعدادات ← المساعدة ← معلومات التطبيق.</p>
            <Done busy={busy} label={has ? "حدّثته" : "ثبّتّه وهو آخر إصدار"} onClick={() => confirm("app")} />
          </>
        );
      case "registered":
        return <RegisteredStep busy={busy} has={has} phone={state.phone} onSave={(v) => confirm("registered", v)} />;
      case "profile":
        return (
          <>
            <p>من التطبيق: <b>الإعدادات ← أدوات الأعمال ← الملف التجاري</b>، ثم أكمل:</p>
            <ul className="small">
              <li><b>اسم النشاط</b>: الاسم الذي يراه العملاء، مثل «{state.officeName}». يثبّت Meta هذا الاسم عند الربط، فراجعه الآن.</li>
              <li><b>الفئة</b>: عقارات.</li>
              <li><b>العنوان وساعات العمل والبريد الإلكتروني</b> للمكتب.</li>
            </ul>
            <Done busy={busy} label="أكملت الملف" onClick={() => confirm("profile")} />
          </>
        );
      case "facebook":
        return (
          <>
            <p>عند الربط تسجّل الدخول في نافذة Meta بحساب فيسبوك. يكفي حسابك الشخصي، ولا يُنشر عليه شيء.</p>
            <p className="small muted">ليس لديك حساب؟ أنشئه من <a href="https://www.facebook.com/r.php" target="_blank" rel="noopener noreferrer">facebook.com</a>. في نافذة الربط تختار حساب أعمال Meta للمكتب أو تنشئ واحداً: اسم المكتب الرسمي، والدولة، وموقعه الإلكتروني إن وُجد.</p>
            <Done busy={busy} label="عندي حساب فيسبوك" onClick={() => confirm("facebook")} />
          </>
        );
      case "active":
        return (
          <>
            <p>يشترط Meta أن يُستخدم الرقم في التطبيق <b>7 أيام على الأقل</b> قبل ربطه. ردّ على عملائك منه كالمعتاد؛ المحادثات الحقيقية تساعد.</p>
            {state.readyOn && (
              <div className="nextstep">
                <div>
                  <span className="small muted">يجهز الربط</span>
                  <h3>{day(state.readyOn)}</h3>
                </div>
                <span className="pill p-warn">بقي {days(state.daysLeft)}</span>
              </div>
            )}
            <p className="small">في الأثناء جهّز حداء: <Link href="/listings">أضف عقاراتك</Link> · <Link href="/marketers">أضف المسوّقين</Link> · <Link href="/try">جرّب الوكيل</Link></p>
            <p className="small muted">يتحقق حداء من هذه الخطوة تلقائياً. التاريخ خطأ؟ عدّل خطوة «{label("registered")}».</p>
          </>
        );
      case "connect":
        return (
          <>
            <p className="small muted">افتح هذه الصفحة على الكمبيوتر إن استطعت، فستمسح رمز QR بالجوال.</p>
            <ol className="small howto">
              <li>اضغط «اربط عبر Meta» وسجّل الدخول بفيسبوك.</li>
              <li>اختر حساب أعمال المكتب أو أنشئ واحداً.</li>
              <li>اختر ربط تطبيق واتساب للأعمال، واكتب رقم المكتب{state.phone ? <> <bdi className="mono">{state.phone}</bdi></> : null}.</li>
              <li>تصلك في التطبيق رسالة من واتساب: افتحها، وامسح رمز QR الظاهر على الشاشة، ووافق على مشاركة المحادثات.</li>
              <li>ارجع إلى نافذة Meta وأكملها. يتأكد حداء من الربط فوراً.</li>
            </ol>
            {readyToConnect ? connect : <p className="small muted">أكمل الخطوات السابقة أولاً.</p>}
            <details className="small">
              <summary>مشاكل شائعة</summary>
              <ul>
                <li><b>«الرقم غير مؤهل»</b>: استمر في استخدام الرقم في التطبيق أياماً أخرى، ثم أعد المحاولة.</li>
                <li><b>«حدّث التطبيق»</b>: حدّثه من المتجر ثم أعد المحاولة.</li>
                <li><b>الرقم مربوط بمنصة أو روبوت آخر</b>: افصله منها أولاً.</li>
                <li><b>لم تصل رسالة الربط في التطبيق</b>: تأكد أن التطبيق محدّث ومفتوح على الجوال، ثم أعد فتح نافذة Meta.</li>
              </ul>
            </details>
          </>
        );
      case "test":
        return null; // shown once connected, on the WhatsApp page
    }
  }

  function label(key: SetupStepKey) {
    return state.steps.find((s) => s.key === key)?.label ?? "";
  }

  function summary(s: WaSetup["steps"][number]): string {
    if (s.key === "number") return state.phone ?? "";
    if (s.key === "registered" && state.businessAppSince) return `منذ ${day(state.businessAppSince)}`;
    if (s.key === "active" && s.done) return "اكتملت";
    return "تم";
  }

  const visible = state.steps.filter((s) => s.key !== "test");
  return (
    <section className="card wasetup" aria-labelledby="wa-h">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1 id="wa-h">اربط رقم واتساب المكتب</h1>
        <span className="small muted">{state.done} من {state.total} خطوات</span>
      </div>
      <p className="small muted">
        {has ? "رقمك على واتساب للأعمال. تأكد من هذه الخطوات ثم اربطه." : "نمشي معك من تثبيت التطبيق حتى أول رسالة من عميل. نحفظ تقدّمك، فتكمل من أي جهاز."}{" "}
        <button type="button" className="linkbtn" disabled={busy} onClick={() => choose(!has)}>{has ? "ليس عندي واتساب للأعمال" : "عندي واتساب للأعمال"}</button>
      </p>
      <ol className="wasteps">
        {visible.map((s, i) => {
          const open = s.key === state.current;
          return (
            <li key={s.key} className={s.done ? "done" : open ? "open" : "later"} aria-current={open ? "step" : undefined}>
              <span className="mark" aria-hidden="true">{s.done ? <Icon name="check" size={16} /> : i + 1}</span>
              <div className="stepbody">
                <div className="stephead">
                  <h2>{s.label}</h2>
                  {s.done && <span className="small muted">{summary(s)}</span>}
                  {s.done && !s.auto && !state.connected && (
                    <button type="button" className="linkbtn small" disabled={busy} onClick={() => undo(s.key)}>تعديل</button>
                  )}
                  {!s.done && s.auto && <span className="pill p-acc">يتحقق حداء</span>}
                  <span className="sr-only">{s.done ? "مكتملة" : open ? "الخطوة الحالية" : "لاحقاً"}</span>
                </div>
                {open && (
                  <div className="stepcontent">
                    {body(s.key)}
                    {error?.key === s.key && <p className="err" role="alert">{error.text}</p>}
                  </div>
                )}
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

function Done({ label, busy, onClick }: { label: string; busy: boolean; onClick: () => void }) {
  return <button className="btn primary" disabled={busy} onClick={onClick}><Icon name="check" size={16} /> {label}</button>;
}

function NumberStep({ busy, has, onSave }: { busy: boolean; has: boolean; onSave: (v: string) => void }) {
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    onSave(String(new FormData(e.currentTarget).get("phone") ?? ""));
  }
  return (
    <form onSubmit={submit} className="stepform">
      <p>{has ? "رقم الجوال المسجّل في واتساب للأعمال للمكتب." : "الرقم الذي سيراسله العملاء: جوال سعودي يستقبل رسالة SMS أو اتصالاً لرمز التحقق، ويبقى معك."}</p>
      {!has && <p className="small muted">إن كان الرقم على واتساب العادي فلا بأس: عند تسجيله في واتساب للأعمال ينقل التطبيق حسابه ومحادثاته.</p>}
      <label className="f" htmlFor="wa-phone">رقم جوال المكتب
        <input id="wa-phone" name="phone" type="tel" inputMode="tel" autoComplete="tel" placeholder="05XXXXXXXX" required />
      </label>
      <button className="btn primary" disabled={busy}><Icon name="check" size={16} /> تأكيد الرقم</button>
    </form>
  );
}

function RegisteredStep({ busy, has, phone, onSave }: { busy: boolean; has: boolean; phone: string | null; onSave: (v: string) => void }) {
  const max = today();
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    onSave(String(new FormData(e.currentTarget).get("since") ?? ""));
  }
  return (
    <form onSubmit={submit} className="stepform">
      {has ? (
        <p>متى بدأ المكتب استخدام هذا الرقم على واتساب للأعمال؟ يكفي تاريخ تقريبي.</p>
      ) : (
        <ol className="small howto">
          <li>افتح تطبيق واتساب للأعمال ووافق على الشروط.</li>
          <li>اكتب رقم المكتب{phone ? <> <bdi className="mono">{phone}</bdi></> : null}، ثم الرمز الذي يصلك برسالة أو اتصال.</li>
          <li>إن سألك عن حساب واتساب موجود على الرقم فاختر المتابعة لنقل محادثاتك.</li>
          <li>اكتب اسم المكتب واختر صورة له، ثم ارجع هنا.</li>
        </ol>
      )}
      <label className="f" htmlFor="wa-since">{has ? "تاريخ البدء" : "تاريخ التسجيل"}
        <input id="wa-since" name="since" type="date" max={max} defaultValue={max} required />
      </label>
      <button className="btn primary" disabled={busy}><Icon name="check" size={16} /> {has ? "تأكيد التاريخ" : "سجّلته"}</button>
    </form>
  );
}

/** The last step, once connected: Hidaa notices the first message from a customer by itself. */
export function FirstMessageStep({ state }: { state: WaSetup }) {
  const step = state.steps.find((s) => s.key === "test");
  if (!step) return null;
  if (step.done) {
    // Shown for a day after the first message, then out of the way.
    if (!step.doneAt || Date.now() - new Date(step.doneAt).getTime() > 86_400_000) return null;
    return (
      <section className="card wasetup ok" aria-live="polite">
        <h2><Icon name="check" size={18} /> وصلت أول رسالة من عميل</h2>
        <p className="small">اكتمل الربط: الوكيل يرد على عملائك الآن من رقم المكتب. <Link href="/inbox">افتح المحادثات</Link></p>
      </section>
    );
  }
  return (
    <section className="card wasetup" aria-labelledby="wa-test-h">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h2 id="wa-test-h">آخر خطوة: جرّب الوكيل</h2>
        <span className="small muted">{state.done} من {state.total} خطوات</span>
      </div>
      <p>من جوال آخر أرسل إلى رقم المكتب رسالة مثل: «السلام عليكم، أبحث عن شقة للإيجار في النرجس».</p>
      <p className="small muted waiting"><span className="pulse" aria-hidden="true" /> بانتظار أول رسالة… تتأكد هذه الخطوة تلقائياً عند وصولها.</p>
    </section>
  );
}
