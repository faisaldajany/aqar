"use client";

import { FormEvent, useEffect, useState } from "react";
import { api, ApiError, type Marketer, type Usage } from "@/lib/api";

/** Marketers receive handoffs for the districts they cover. */
export default function MarketersPage() {
  const [list, setList] = useState<Marketer[]>([]);
  const [usage, setUsage] = useState<Usage | null>(null);
  const [error, setError] = useState("");

  const load = () => {
    api<Marketer[]>("/marketers").then(setList).catch(() => setError("تعذّر التحميل."));
    api<Usage>("/usage").then(setUsage).catch(() => undefined);
  };
  useEffect(load, []);

  async function add(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const f = new FormData(form);
    const raw = String(f.get("phone") ?? "").replace(/\D/g, "");
    const waPhone = raw.startsWith("05") ? "966" + raw.slice(1) : raw.startsWith("5") ? "966" + raw : raw;
    setError("");
    try {
      await api("/marketers", {
        method: "POST",
        json: { name: f.get("name"), waPhone, districts: String(f.get("districts") ?? "").split(/[,،]/).map((s) => s.trim()).filter(Boolean) },
      });
      form.reset();
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "تعذّرت الإضافة.");
    }
  }

  return (
    <>
      <h1>المسوّقون</h1>
      <p className="small muted">يحوّل الوكيل العميل الجاد إلى المسوّق المسؤول عن حيّه مع ملخص، وإذا غطّى الحيَّ أكثر من مسوّق فللأقل انشغالاً. {usage && (usage.marketersLimit === 1 ? "باقتك تسمح بمسوّق واحد." : `باقتك تسمح بـ${usage.marketersLimit} مسوّقين.`)}</p>
      <div className="tbl">
        <table>
          <thead><tr><th>الاسم</th><th>واتساب</th><th>الأحياء</th><th></th></tr></thead>
          <tbody>
            {list.map((m) => (
              <tr key={m.id}>
                <td>{m.name}</td>
                <td className="mono">{m.waPhone}</td>
                <td>{m.districts.join("، ") || <span className="muted">كل الأحياء</span>}</td>
                <td><button className="btn" onClick={async () => { await api(`/marketers/${m.id}/deactivate`, { method: "POST" }); load(); }}>إيقاف</button></td>
              </tr>
            ))}
            {list.length === 0 && <tr><td colSpan={4} className="muted">لم يُضف مسوّقون بعد.</td></tr>}
          </tbody>
        </table>
      </div>
      <form className="card grid3" onSubmit={add} style={{ alignItems: "end" }}>
        <label className="f" htmlFor="name">الاسم<input id="name" name="name" type="text" required /></label>
        <label className="f" htmlFor="phone">جوال واتساب<input id="phone" name="phone" type="text" dir="ltr" required placeholder="05XXXXXXXX" /></label>
        <label className="f" htmlFor="districts">الأحياء <span className="small muted">مفصولة بفاصلة</span><input id="districts" name="districts" type="text" placeholder="النرجس، الياسمين" /></label>
        <div><button className="btn primary">إضافة مسوّق</button></div>
      </form>
      {error && <p className="err" role="alert">{error}</p>}
      <Routing />
      <Performance />
    </>
  );
}

/** Moving a customer on when the marketer does not answer in time. */
function Routing() {
  const [minutes, setMinutes] = useState<number | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { api<{ timeoutMinutes: number | null }>("/marketers/routing").then((r) => setMinutes(r.timeoutMinutes)).catch(() => undefined); }, []);
  if (minutes === undefined) return null;

  async function change(v: string) {
    setBusy(true);
    setError("");
    try {
      const r = await api<{ timeoutMinutes: number | null }>("/marketers/routing", { method: "PUT", json: { timeoutMinutes: v ? Number(v) : null } });
      setMinutes(r.timeoutMinutes);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر الحفظ.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card followups" aria-labelledby="routing-h">
      <div>
        <h2 id="routing-h">إذا لم يرد المسوّق</h2>
        <p className="small muted" style={{ margin: 0 }}>
          يُحوّل العميل إلى مسوّق آخر يغطي حيّه (أو أي مسوّق متاح) إذا لم يرد أحد من المكتب في المحادثة ولم يستلمه أحد من صفحة التحويلات،
          بين 8 صباحاً و11 مساءً. إذا لم يرد أحد منهم يصلك تنبيه. والعملاء الجدد يوزّعون أصلاً على الأقل انشغالاً.
        </p>
      </div>
      <select aria-label="مهلة الرد" value={minutes ?? ""} disabled={busy} onChange={(e) => void change(e.target.value)}>
        <option value="">لا تحوّله</option>
        {[10, 15, 30, 60].map((m) => <option key={m} value={m}>بعد {m === 60 ? "ساعة" : `${m} دقيقة`} بلا رد</option>)}
      </select>
      {error && <p className="err" role="alert">{error}</p>}
    </section>
  );
}

type Perf = {
  marketerId: string; name: string; active: boolean; received: number; answered: number; movedOn: number; open: number;
  medianAnswerMinutes: number | null; viewingsDone: number; noShows: number; upcoming: number; deals: number; dealValue: number;
};

function minutesAr(m: number | null): string {
  if (m == null) return "—";
  if (m < 1) return "أقل من دقيقة";
  if (m < 60) return `${Math.round(m)} د`;
  const h = m / 60;
  return h < 24 ? `${h.toFixed(h < 10 ? 1 : 0)} س` : `${Math.round(h / 24)} يوم`;
}

/** Each marketer's customers, answers, viewings and deals, over a chosen period. */
function Performance() {
  const [days, setDays] = useState<7 | 30 | 90>(30);
  const [rows, setRows] = useState<Perf[] | null>(null);
  useEffect(() => { api<Perf[]>(`/marketers/performance?days=${days}`).then(setRows).catch(() => setRows([])); }, [days]);
  if (rows === null) return null;
  return (
    <section aria-labelledby="perf-h" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h2 id="perf-h">أداء المسوّقين</h2>
        <div className="periods" role="group" aria-label="الفترة">
          {([[7, "7 أيام"], [30, "30 يوماً"], [90, "90 يوماً"]] as const).map(([d, l]) => (
            <button key={d} type="button" aria-pressed={days === d} onClick={() => setDays(d)}>{l}</button>
          ))}
        </div>
      </div>
      {rows.length === 0 ? <p className="small muted">لا مسوّقين بعد.</p> : (
        <div className="tbl"><table>
          <thead><tr>
            <th>المسوّق</th><th>عملاء استلمهم</th><th>ردّ عليهم</th><th>متوسط زمن الرد</th><th>حُوّلوا لغيره</th>
            <th>معاينات تمت</th><th>لم يحضر العميل</th><th>صفقات</th>
          </tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.marketerId} style={{ opacity: r.active ? 1 : 0.6 }}>
                <td><b>{r.name}</b>{!r.active && <span className="small muted"> (موقوف)</span>}</td>
                <td className="num">{r.received}{r.open > 0 && <span className="small muted"> · {r.open} بانتظاره</span>}</td>
                <td className="num">{r.received ? `${r.answered} (${Math.round((r.answered / r.received) * 100)}٪)` : "—"}</td>
                <td className="num">{minutesAr(r.medianAnswerMinutes)}</td>
                <td className="num">{r.movedOn || "—"}</td>
                <td className="num">{r.viewingsDone}{r.upcoming > 0 && <span className="small muted"> · {r.upcoming} قادمة</span>}</td>
                <td className="num">{r.noShows || "—"}</td>
                <td className="num">{r.deals}{r.dealValue > 0 && <span className="small muted"> · {Math.round(r.dealValue).toLocaleString("en-US")} ريال</span>}</td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}
      <p className="small muted">«ردّ عليهم»: كتب أحد من المكتب في المحادثة، أو استلم التحويل من صفحة التحويلات، قبل أن يُحوّل العميل لغيره. الصفقات من قائمة العملاء.</p>
    </section>
  );
}
