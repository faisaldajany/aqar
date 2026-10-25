"use client";

import { useState } from "react";
import { api, ApiError, type ImportCommitted, type ImportPreview } from "@/lib/api";

const STATUS: Record<string, [string, string]> = {
  READY: ["جاهز", "p-ok"],
  WARNING: ["يُحفظ مع ملاحظة", "p-warn"],
  INVALID: ["لن يُستورد", "p-bad"],
};

/** Upload → check the column mapping and preview → import. Re-importing updates listings by ad license. */
export default function ImportWizard({ onDone }: { onDone: () => void }) {
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [mapping, setMapping] = useState<Record<string, number | null>>({});
  const [result, setResult] = useState<ImportCommitted | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function upload(file: File) {
    setBusy(true);
    setError("");
    const form = new FormData();
    form.append("file", file);
    try {
      const p = await api<ImportPreview>("/imports", { method: "POST", body: form });
      setPreview(p);
      setMapping(Object.fromEntries(p.fields.map((f) => [f.field, f.column])));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر قراءة الملف.");
    } finally {
      setBusy(false);
    }
  }

  async function remap(field: string, column: number | null) {
    if (!preview) return;
    const next = { ...mapping, [field]: column };
    setMapping(next);
    try {
      setPreview(await api<ImportPreview>(`/imports/${preview.batchId}/preview`, { method: "POST", json: { mapping: next } }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر تحديث المعاينة.");
    }
  }

  async function commit() {
    if (!preview) return;
    setBusy(true);
    setError("");
    try {
      setResult(await api<ImportCommitted>(`/imports/${preview.batchId}/commit`, { method: "POST", json: { mapping } }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "تعذّر الاستيراد.");
    } finally {
      setBusy(false);
    }
  }

  if (result) {
    return (
      <div className="card" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <h2>تم الاستيراد</h2>
        <p>أُضيف <b>{result.created}</b> عقاراً، وحُدّث <b>{result.updated}</b>، ولم يُستورد <b>{result.skipped}</b>.</p>
        {result.skippedRows.length > 0 && (
          <ul className="small">{result.skippedRows.map((r) => <li key={r.row}>صف {r.row}: {r.errors.join("، ")}</li>)}</ul>
        )}
        <div><button className="btn primary" onClick={onDone}>إلى العقارات</button></div>
      </div>
    );
  }

  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <h2>استيراد من ملف Excel أو CSV</h2>
      {!preview ? (
        <>
          <p className="small muted">أول صف عناوين الأعمدة. نتعرّف على الأعمدة تلقائياً ونعرض النتيجة قبل الحفظ. يقبل التاريخ الهجري والأرقام العربية، ورفع الملف مرة أخرى يحدّث العقارات الموجودة حسب رقم الترخيص.</p>
          <input type="file" accept=".xlsx,.xls,.csv" disabled={busy} onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} aria-label="اختر الملف" />
        </>
      ) : (
        <>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <b>{preview.fileName} · {preview.total} صفاً</b>
            <span className="row small">
              <span className="pill p-ok">جاهز {preview.ready}</span>
              <span className="pill p-warn">ملاحظات {preview.withWarnings}</span>
              <span className="pill p-bad">مرفوض {preview.invalid}</span>
            </span>
          </div>
          <div className="grid3">
            {preview.fields.map((f) => (
              <label key={f.field} className="f" htmlFor={`map-${f.field}`}>{f.label}{f.required ? " *" : ""}
                <select id={`map-${f.field}`} value={mapping[f.field] ?? ""} onChange={(e) => remap(f.field, e.target.value === "" ? null : Number(e.target.value))}>
                  <option value="">— غير موجود —</option>
                  {preview.headers.map((h, i) => <option key={i} value={i}>{h || `عمود ${i + 1}`}</option>)}
                </select>
              </label>
            ))}
          </div>
          <div className="tbl">
            <table>
              <thead><tr><th>الصف</th><th>العقار</th><th>السعر</th><th>الترخيص</th><th>الانتهاء</th><th>الحالة</th></tr></thead>
              <tbody>
                {preview.rows.map((r) => (
                  <tr key={r.row}>
                    <td>{r.row}</td>
                    <td>{r.title}<div className="small muted">{r.district}</div></td>
                    <td>{r.price}</td>
                    <td className="mono">{r.license}</td>
                    <td>{r.expiry}</td>
                    <td><span className={`pill ${STATUS[r.status][1]}`}>{STATUS[r.status][0]}</span>
                      {[...r.errors, ...r.warnings].length > 0 && <div className="small muted">{[...r.errors, ...r.warnings].join("، ")}</div>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {preview.total > preview.rows.length && <p className="small muted">تُعرض أول {preview.rows.length} صفاً.</p>}
          <div className="row">
            <button className="btn primary" disabled={busy || preview.ready + preview.withWarnings === 0} onClick={commit}>
              استورد {preview.ready + preview.withWarnings} عقاراً
            </button>
            <button className="btn" onClick={() => { void api(`/imports/${preview.batchId}/discard`, { method: "POST" }); setPreview(null); }}>ملف آخر</button>
          </div>
        </>
      )}
      {error && <p className="err" role="alert">{error}</p>}
      <div><button className="btn" onClick={onDone}>رجوع</button></div>
    </div>
  );
}
