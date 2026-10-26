"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Icon } from "@/components/Icon";
import { api, type Alert, type Onboarding, type Stats, type Usage } from "@/lib/api";
import { useSession } from "../session";

const STEP_HINT: Record<string, string> = {
  whatsapp: "الرقم نفسه والتطبيق نفسه، من نافذة Meta الرسمية.",
  listings: "ملف Excel أو رابط إعلان من عقار. الوكيل لا يعرض إلا ما هنا.",
  marketers: "لكل مسوّق أحياؤه، ليصل كل عميل جاد للشخص الصحيح.",
  booking: "أيام وساعات المعاينة، وصيغة رسالة التأكيد للعميل.",
  report: "ملخص يومك يصل جوالك كل مساء.",
  test: "حادث وكيلك كأنك عميل، قبل أن يرد على عملائك.",
  subscribe: "اختر باقتك قبل نهاية التجربة ليستمر الوكيل دون توقف.",
};

export default function Dashboard() {
  const { me } = useSession();
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [usage, setUsage] = useState<Usage | null>(null);
  const [stats, setStats] = useState<Stats | null>(null);
  const [onb, setOnb] = useState<Onboarding | null>(null);
  const [showDone, setShowDone] = useState(false);

  useEffect(() => {
    api<Alert[]>("/alerts?unread=true").then(setAlerts).catch(() => undefined);
    api<Usage>("/usage").then(setUsage).catch(() => undefined);
    api<Stats>("/stats").then(setStats).catch(() => undefined);
    api<Onboarding>("/onboarding").then(setOnb).catch(() => undefined);
  }, []);

  async function dismiss(id: string) {
    await api(`/alerts/${id}/read`, { method: "POST" });
    setAlerts((a) => a.filter((x) => x.id !== id));
  }

  const next = onb?.steps.find((s) => !s.done);
  const rest = onb?.steps.filter((s) => !s.done && s !== next) ?? [];
  const done = onb?.steps.filter((s) => s.done) ?? [];
  const quiet = stats && stats.inquiriesWeek === 0 && stats.bookedWeek === 0 && stats.openHandoffs === 0;
  const mockWa = me.whatsapp.connected && me.whatsapp.verifiedName === "مكتب تجريبي";

  return (
    <>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1 className="greet">أهلاً {me.user.fullName.split(" ")[0]}</h1>
        {onb?.subscription === "TRIAL" && <Link href="/billing" className="pill p-acc">باقي {onb.trialDaysLeft} يوماً من التجربة</Link>}
      </div>

      {onb && !onb.serving && (
        <div className="banner bad" role="alert">
          <b>الوكيل متوقف عن الرد</b> لأن الاشتراك غير فعّال، ورسائل العملاء تنتظرك في المحادثات.
          <Link href="/billing" className="btn primary">فعّل الاشتراك</Link>
        </div>
      )}
      {mockWa && (
        <div className="banner">
          <span>الرقم المربوط تجريبي، فلن تصل رسائل عملاء حقيقيين. جرّب الوكيل من البوابة الآن، واربط رقم المكتب الحقيقي حين يتاح.</span>
          <Link href="/try" className="btn">جرّب الوكيل</Link>
        </div>
      )}

      {next && onb && (
        <section className="card setup" aria-labelledby="setup-h">
          <div className="row" style={{ justifyContent: "space-between" }}>
            <h2 id="setup-h">جهّز وكيلك</h2>
            <span className="small muted">{onb.done} من {onb.total} خطوات</span>
          </div>
          <div className="dots" role="progressbar" aria-valuenow={onb.done} aria-valuemin={0} aria-valuemax={onb.total} aria-label="تقدّم التجهيز">
            {onb.steps.map((st) => <i key={st.key} className={st.done ? "on" : st === next ? "next" : ""} />)}
          </div>
          <div className="nextstep">
            <div>
              <span className="small muted">الخطوة التالية</span>
              <h3>{next.label}</h3>
              <p className="small muted">{STEP_HINT[next.key]}</p>
            </div>
            <Link href={next.href} className="btn reply">ابدأ</Link>
          </div>
          {rest.length > 0 && (
            <ul className="steplist">
              {rest.map((s) => <li key={s.key}><Link href={s.href}>{s.label}</Link></li>)}
            </ul>
          )}
          {done.length > 0 && (
            <button type="button" className="linkbtn small" onClick={() => setShowDone((v) => !v)} aria-expanded={showDone}>
              {showDone ? "إخفاء المكتمل" : `المكتمل (${done.length})`}
            </button>
          )}
          {showDone && <ul className="steplist done">{done.map((s) => <li key={s.key}><Icon name="check" size={16} />{s.label}</li>)}</ul>}
          {onb.falStatus === "PENDING_REVIEW" && <p className="small muted">نتحقق من رخصة فال خلال يوم عمل، والوكيل يعمل خلال ذلك.</p>}
        </section>
      )}

      {alerts.length > 0 && (
        <section className="card" aria-labelledby="alerts-h">
          <div className="row" style={{ justifyContent: "space-between" }}>
            <h2 id="alerts-h">يحتاج انتباهك</h2>
            <button className="linkbtn small" onClick={async () => { await api("/alerts/read-all", { method: "POST" }); setAlerts([]); }}>تعليم الكل كمقروء</button>
          </div>
          <ul className="alerts">
            {alerts.map((a) => (
              <li key={a.id}>
                <span>{a.message}</span>
                <span className="row">
                  {a.kind.startsWith("LICENSE") && <Link className="btn" href="/listings">العقارات</Link>}
                  {a.kind === "SELF_VISIT_REQUEST" && <Link className="btn" href="/bookings">راجع الطلب</Link>}
                  <button className="btn" onClick={() => dismiss(a.id)}>تم</button>
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {stats && (quiet ? (
        <section className="card emptystate">
          <h2>أرقام يومك تظهر هنا</h2>
          <p className="muted">بعد أول محادثة ترى: الاستفسارات، والعملاء الجادين، والمواعيد، والتحويلات، وسرعة الرد.</p>
          <Link href="/try" className="btn">ابدأ محادثة تجريبية</Link>
        </section>
      ) : (
        <section className="kpis" aria-label="أرقام اليوم والأسبوع">
          <Link href="/inbox" className="kpi"><b>{stats.inquiriesToday}</b><span>استفسار اليوم، {stats.inquiriesWeek} هذا الأسبوع</span></Link>
          <Link href="/inbox" className="kpi"><b>{stats.qualifiedWeek}</b><span>عميل اكتمل طلبه هذا الأسبوع</span></Link>
          <Link href="/bookings" className="kpi"><b>{stats.bookingsToday}</b><span>موعد اليوم، {stats.bookedWeek} حُجز هذا الأسبوع</span></Link>
          <Link href="/handoffs" className="kpi"><b>{stats.openHandoffs}</b><span>عميل بانتظار مسوّق</span></Link>
          <div className="kpi"><b>{stats.afterHoursWeek}</b><span>رسالة بعد الدوام هذا الأسبوع</span></div>
          <div className="kpi"><b>{stats.medianReplyMs == null ? "—" : `${(stats.medianReplyMs / 1000).toFixed(1)} ث`}</b><span>متوسط سرعة الرد</span></div>
        </section>
      ))}

      <div className="grid2">
        <section className="card">
          <h2>رقم واتساب المكتب</h2>
          {me.whatsapp.connected ? (
            <p className="row"><span className="mono">{me.whatsapp.displayPhone}</span>{mockWa ? <span className="pill p-warn">تجريبي</span> : <span className="pill p-ok">مربوط</span>}</p>
          ) : (
            <p className="muted">لم يُربط بعد. <Link href="/whatsapp">اربط الرقم</Link></p>
          )}
        </section>
        {usage && (
          <section className="card">
            <h2>باقة {usage.planName}</h2>
            <p className="small">{usage.conversations.toLocaleString("en-US")} من {usage.included.toLocaleString("en-US")} محادثة هذا الشهر</p>
            <div className="meter" role="progressbar" aria-valuenow={usage.percent} aria-valuemin={0} aria-valuemax={100} aria-label="استهلاك الردود">
              <i style={{ width: `${Math.min(usage.percent, 100)}%` }} />
            </div>
            {usage.overage > 0 && <p className="small" style={{ color: "var(--warn)" }}>محادثات إضافية: {usage.overage} ({usage.overageSar} ريال)</p>}
          </section>
        )}
      </div>
    </>
  );
}
