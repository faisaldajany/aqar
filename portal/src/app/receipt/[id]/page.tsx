"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Logo } from "@/components/Logo";
import { api, ApiError, type Invoice } from "@/lib/api";

type Receipt = {
  invoice: Invoice;
  paidMethod: string | null; paidLast4: string | null; paidReference: string | null;
  office: { name: string; falLicense: string | null; email: string | null };
  seller: { name: string; vatNumber: string | null; cr: string | null; address: string | null };
  vatPercent: number;
  test: boolean;
};

const money = (h: number) => `${(h / 100).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ريال`;
const day = (iso: string) => new Date(iso.length === 10 ? `${iso}T12:00:00` : iso)
  .toLocaleDateString("ar-SA-u-ca-gregory-nu-latn", { day: "numeric", month: "long", year: "numeric", timeZone: "Asia/Riyadh" });
const when = (iso: string) => new Date(iso)
  .toLocaleString("ar-SA-u-ca-gregory-nu-latn", { day: "numeric", month: "long", year: "numeric", hour: "numeric", minute: "2-digit", timeZone: "Asia/Riyadh" });

const METHOD: Record<string, string> = {
  mada: "مدى", visa: "فيزا", master: "ماستركارد", mastercard: "ماستركارد", amex: "أمريكان إكسبريس",
  applepay: "Apple Pay", samsungpay: "Samsung Pay", stcpay: "STC Pay", MANUAL: "تحويل بنكي",
};

/** A payment receipt (or a due invoice) in Hidaa's design, to read, print or save as PDF. */
export default function ReceiptPage() {
  const { id } = useParams<{ id: string }>();
  const [r, setR] = useState<Receipt | null>(null);
  const [error, setError] = useState<{ status: number; text: string } | null>(null);

  useEffect(() => {
    api<Receipt>(`/billing/invoices/${id}/receipt`).then(setR)
      .catch((e) => setError({ status: e instanceof ApiError ? e.status : 0, text: e instanceof ApiError ? e.message : "تعذّر التحميل." }));
  }, [id]);

  if (error) {
    return (
      <main className="auth">
        <div className="card">
          {error.status === 401 ? (
            <><h1>سجّل الدخول لعرض الإيصال</h1><p className="muted">الإيصال متاح لحساب المكتب فقط.</p><Link className="btn primary" href="/login">تسجيل الدخول</Link></>
          ) : <p className="err">{error.text}</p>}
        </div>
      </main>
    );
  }
  if (!r) return <main className="auth"><p className="muted">جارٍ التحميل…</p></main>;

  const i = r.invoice;
  const paid = i.status === "PAID" || i.status === "REFUNDED";
  const title = i.status === "REFUNDED" ? "إيصال دفع (مسترد)" : paid ? "إيصال دفع" : i.status === "DUE" ? "فاتورة مستحقة" : "فاتورة ملغاة";
  const method = r.paidMethod ? `${METHOD[r.paidMethod] ?? r.paidMethod}${r.paidLast4 ? ` •••• ${r.paidLast4}` : ""}` : null;

  return (
    <main className="receipt-wrap">
      <div className="receipt-actions noprint">
        <Link href="/billing" className="btn">← الاشتراك</Link>
        <button type="button" className="btn primary" onClick={() => window.print()}>طباعة أو حفظ PDF</button>
      </div>
      <article className="receipt" aria-labelledby="r-title">
        <header>
          <Logo size={30} />
          <div className="r-head">
            <h1 id="r-title">{title}</h1>
            <span className="mono">{i.number}</span>
          </div>
        </header>
        {r.test && <p className="r-test">دفع تجريبي لحساب داخلي لفريق حداء (بطاقات اختبار ميسّر): لم يُخصم أي مبلغ.</p>}

        <section className="r-parties">
          <div>
            <h2>من</h2>
            <b>{r.seller.name}</b>
            {r.seller.vatNumber && <div>الرقم الضريبي: <bdi className="mono">{r.seller.vatNumber}</bdi></div>}
            {r.seller.cr && <div>السجل التجاري: <bdi className="mono">{r.seller.cr}</bdi></div>}
            {r.seller.address && <div>{r.seller.address}</div>}
            <div><bdi>support@hidaaco.com</bdi></div>
          </div>
          <div>
            <h2>إلى</h2>
            <b>{r.office.name}</b>
            {r.office.falLicense && <div>رخصة فال: <bdi className="mono">{r.office.falLicense}</bdi></div>}
            {r.office.email && <div><bdi>{r.office.email}</bdi></div>}
          </div>
          <div>
            <h2>التاريخ</h2>
            <div>أُصدرت: {day(i.createdAt)}</div>
            {i.paidAt && <div>دُفعت: {when(i.paidAt)}</div>}
            {i.refundedAt && <div>استُردت: {day(i.refundedAt)}</div>}
          </div>
        </section>

        <table className="r-items">
          <thead><tr><th>البيان</th><th>المبلغ</th></tr></thead>
          <tbody>
            <tr>
              <td>{i.description}{i.periodStart && i.periodEnd && <div className="small muted">{day(i.periodStart)} – {day(i.periodEnd)}</div>}</td>
              <td className="num">{money(i.subtotalHalalas + i.discountHalalas)}</td>
            </tr>
            {i.discountHalalas > 0 && <tr><td>خصم{i.promoCode ? ` (${i.promoCode})` : ""}</td><td className="num">− {money(i.discountHalalas)}</td></tr>}
          </tbody>
          <tfoot>
            <tr><td>المبلغ قبل الضريبة</td><td className="num">{money(i.subtotalHalalas)}</td></tr>
            <tr><td>ضريبة القيمة المضافة {r.vatPercent}٪</td><td className="num">{money(i.vatHalalas)}</td></tr>
            <tr className="total"><td>{paid ? "المدفوع" : "الإجمالي المستحق"}</td><td className="num">{money(i.totalHalalas)}</td></tr>
            {i.refundedHalalas > 0 && <tr><td>المسترد</td><td className="num">− {money(i.refundedHalalas)}</td></tr>}
          </tfoot>
        </table>

        {paid && (
          <dl className="r-pay">
            {method && <><dt>طريقة الدفع</dt><dd>{method}</dd></>}
            {r.paidReference && <><dt>{r.paidMethod === "MANUAL" ? "مرجع التحويل" : "مرجع العملية"}</dt><dd><bdi className="mono">{r.paidReference}</bdi></dd></>}
          </dl>
        )}
        <footer className="small muted">إيصال دفع صادر من حداء، وكيل لمكاتب العقار · hidaaco.com</footer>
      </article>
    </main>
  );
}
