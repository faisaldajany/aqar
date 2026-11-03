"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { BarList, ColumnChart, Delta, num, SplitBar, type ChartDatum } from "@/components/Charts";
import { Icon } from "@/components/Icon";
import { api, type Alert, type Demand, type Insights, type Onboarding, type Stats, type Usage } from "@/lib/api";
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

const PERIODS: [7 | 30 | 90, string][] = [[7, "7 أيام"], [30, "30 يوماً"], [90, "90 يوماً"]];
const LOCALE = "ar-SA-u-ca-gregory-nu-latn";

function dayLabel(iso: string) {
  const d = new Date(`${iso}T12:00:00`);
  return `${d.getDate()}/${d.getMonth() + 1}`;
}

function dayTitle(iso: string) {
  return new Date(`${iso}T12:00:00`).toLocaleDateString(LOCALE, { weekday: "long", day: "numeric", month: "long" });
}

function hourLabel(h: number) {
  const n = h % 12 === 0 ? 12 : h % 12;
  return `${n}${h < 12 ? "ص" : "م"}`;
}

const AFTER_HOURS = (h: number) => h >= 22 || h < 8;

/** "3 عقارات" next to a district or type customers ask for, or a warning when the office has none there. */
function supply(d: Demand) {
  if (d.listings === 0) return <span className="pill p-warn">لا عقارات</span>;
  return <span className="muted">{d.listings === 1 ? "عقار واحد" : d.listings === 2 ? "عقاران" : `${d.listings} عقارات`}</span>;
}

export default function Dashboard() {
  const { me } = useSession();
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [usage, setUsage] = useState<Usage | null>(null);
  const [stats, setStats] = useState<Stats | null>(null);
  const [onb, setOnb] = useState<Onboarding | null>(null);
  const [showDone, setShowDone] = useState(false);
  const [days, setDays] = useState<7 | 30 | 90>(30);
  const [ins, setIns] = useState<Insights | null>(null);
  const [loadingIns, setLoadingIns] = useState(false);

  useEffect(() => {
    setLoadingIns(true);
    api<Insights>(`/insights?days=${days}`).then(setIns).catch(() => undefined).finally(() => setLoadingIns(false));
  }, [days]);

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
  const quiet = ins && stats && ins.totals.inquiries === 0 && ins.previous.inquiries === 0 && ins.totals.bookings === 0 && stats.openHandoffs === 0;
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

      {ins && stats && (
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2>أرقام المكتب</h2>
          <div className="periods" role="group" aria-label="الفترة">
            {PERIODS.map(([d, l]) => <button key={d} type="button" aria-pressed={days === d} onClick={() => setDays(d)}>{l}</button>)}
          </div>
        </div>
      )}

      {ins && stats && (quiet ? (
        <section className="card emptystate">
          <h2>أرقامك تظهر هنا بعد أول عميل</h2>
          <p className="muted">ترى هنا الاستفسارات يوماً بيوم، والعملاء الجادين، والمواعيد، والأحياء التي يطلبها العملاء ولا عقارات لك فيها. المحادثات التجريبية لا تُحسب.</p>
          <div className="row">
            <Link href="/try" className="btn">جرّب الوكيل</Link>
            {!me.whatsapp.connected || mockWa ? <Link href="/whatsapp" className="btn">اربط رقم المكتب</Link> : null}
          </div>
        </section>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 12, opacity: loadingIns ? 0.6 : 1, transition: "opacity .2s" }}>
          <section className="kpis" aria-label={`أرقام آخر ${ins.days} يوماً`}>
            <Link href="/inbox" className="kpi">
              <div className="kpi-top"><b>{num(ins.totals.inquiries)}</b><Delta now={ins.totals.inquiries} before={ins.previous.inquiries} /></div>
              <span>استفسار جديد</span>
            </Link>
            <Link href="/inbox" className="kpi">
              <div className="kpi-top"><b>{num(ins.totals.serious)}</b><Delta now={ins.totals.serious} before={ins.previous.serious} /></div>
              <span>عميل جاد (عرف ميزانيته وطريقة الدفع)</span>
            </Link>
            <Link href="/bookings" className="kpi">
              <div className="kpi-top"><b>{num(ins.totals.bookings)}</b><Delta now={ins.totals.bookings} before={ins.previous.bookings} /></div>
              <span>موعد معاينة حُجز{stats.bookingsToday > 0 ? `، ${stats.bookingsToday} اليوم` : ""}</span>
            </Link>
            <Link href="/handoffs" className="kpi">
              <div className="kpi-top"><b>{num(ins.totals.handoffs)}</b><Delta now={ins.totals.handoffs} before={ins.previous.handoffs} /></div>
              <span>تحويل لمسوّق{stats.openHandoffs > 0 ? `، ${stats.openHandoffs} بانتظار الرد` : ""}</span>
            </Link>
            <div className="kpi">
              <b>{ins.hours.messages === 0 ? "—" : `${Math.round((ins.hours.afterHours / ins.hours.messages) * 100)}٪`}</b>
              <span>من رسائل العملاء بعد الدوام، رد عليها الوكيل</span>
            </div>
            <div className="kpi">
              <b>{ins.medianReplyMs == null ? "—" : `${(ins.medianReplyMs / 1000).toFixed(1)} ث`}</b>
              <span>متوسط سرعة رد الوكيل</span>
            </div>
          </section>

          <section className="card" aria-labelledby="daily-h">
            <h2 id="daily-h">الاستفسارات يوماً بيوم</h2>
            <p className="small muted">كل عمود عملاء يوم واحد، والجزء الداكن منهم عملاء جادون.</p>
            <ColumnChart
              label="الاستفسارات يومياً"
              series={[{ key: "serious", label: "عملاء جادون", color: "var(--viz-1)" }, { key: "other", label: "استفسارات أخرى", color: "var(--viz-1-soft)" }]}
              data={ins.series.map((d): ChartDatum => ({ label: dayLabel(d.date), title: dayTitle(d.date),
                values: { serious: d.serious, other: Math.max(0, d.inquiries - d.serious) } }))}
            />
          </section>

          <div className="grid2" style={{ alignItems: "stretch" }}>
            <section className="card" style={{ alignSelf: "stretch" }} aria-labelledby="districts-h">
              <h2 id="districts-h">الأحياء التي يطلبها عملاؤك</h2>
              {ins.topDistricts.length === 0 ? <p className="small muted">لم يذكر العملاء أحياء بعد.</p> : (
                <>
                  <BarList label="طلبات العملاء حسب الحي" rows={ins.topDistricts.map((d) => ({ name: d.name, value: d.requests, note: supply(d) }))} />
                  {ins.topDistricts.some((d) => d.listings === 0) && (
                    <p className="small" style={{ marginBottom: 0 }}>
                      عملاء يسألون عن أحياء ليس لديك فيها عقار. <Link href="/listings">أضف عقارات</Link> لتصلهم.
                    </p>
                  )}
                </>
              )}
            </section>
            <section className="card" style={{ alignSelf: "stretch", display: "flex", flexDirection: "column", gap: 14 }} aria-labelledby="types-h">
              <h2 id="types-h">ماذا يريدون</h2>
              {ins.topTypes.length > 0 && <BarList label="طلبات العملاء حسب نوع العقار" rows={ins.topTypes.map((d) => ({ name: d.name, value: d.requests, note: supply(d) }))} />}
              <div>
                <h3 className="small muted" style={{ fontFamily: "var(--f-body)", marginBottom: 6 }}>شراء أم إيجار</h3>
                <SplitBar label="شراء أم إيجار" parts={[
                  { label: "شراء", value: ins.purpose.SALE ?? 0, color: "var(--viz-1)" },
                  { label: "إيجار", value: ins.purpose.RENT ?? 0, color: "var(--viz-2)" }]} />
              </div>
              <div>
                <h3 className="small muted" style={{ fontFamily: "var(--f-body)", marginBottom: 6 }}>جدية العملاء</h3>
                <SplitBar label="جدية العملاء" parts={[
                  { label: "جاد", value: ins.temperature.HOT ?? 0, color: "var(--viz-1)" },
                  { label: "مهتم", value: ins.temperature.WARM ?? 0, color: "var(--viz-2)" },
                  { label: "يستكشف", value: ins.temperature.COLD ?? 0, color: "var(--viz-3)" }]} />
              </div>
            </section>
          </div>

          {ins.hours.messages > 0 && (
            <section className="card" aria-labelledby="hours-h">
              <h2 id="hours-h">متى يكتب عملاؤك</h2>
              <p className="small muted">رسائل العملاء حسب ساعة اليوم. بعد الدوام (10 م – 8 ص) يرد الوكيل فوراً بدل أن ينتظر العميل للصباح.</p>
              <ColumnChart
                label="رسائل العملاء حسب الساعة"
                height={150}
                series={[{ key: "messages", label: "رسالة", color: "var(--viz-1-soft)" }]}
                highlight={(_, i) => (AFTER_HOURS(i) ? "var(--viz-2)" : undefined)}
                data={ins.hours.byHour.map((v, h): ChartDatum => ({ label: hourLabel(h), title: `${hourLabel(h)} – ${hourLabel((h + 1) % 24)}${AFTER_HOURS(h) ? " (بعد الدوام)" : ""}`, values: { messages: v } }))}
              />
              <ul className="legend" aria-hidden="true">
                <li><i style={{ background: "var(--viz-1-soft)" }} />وقت الدوام</li>
                <li><i style={{ background: "var(--viz-2)" }} />بعد الدوام</li>
              </ul>
            </section>
          )}
        </div>
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
