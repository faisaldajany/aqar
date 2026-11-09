/** The legal entity behind Hidaa, as printed on its commercial registration. */
export const COMPANY = {
  legalName: "شركة غين وجذور",
  legalNameEn: "Ghain and Roots Company",
  form: "شركة ذات مسؤولية محدودة",
  formEn: "limited liability company",
  cr: "7050828479",
  address: "الرياض، حي الوزارات، شارع أبي تمام الهاشمي",
  addressEn: "Abi Tammam Al Hashimi St, Al Wizarat, Riyadh, Saudi Arabia",
} as const;

/**
 * Who owns Hidaa, on every public page. Meta's business verification checks that the site shows the legal name
 * (and that its address matches the documents), so keep this in the server-rendered HTML, word for word as on the CR.
 * The English line keeps the Arabic legal name next to the English one.
 */
export function CompanyLine({ className, lang = "ar" }: { className?: string; lang?: "ar" | "en" }) {
  if (lang === "en") {
    return (
      <p className={className} lang="en" dir="ltr">
        Hidaa is owned by <b>{COMPANY.legalNameEn}</b> (<bdi lang="ar">{COMPANY.legalName}</bdi>), a {COMPANY.formEn} · Commercial
        Registration: <bdi>{COMPANY.cr}</bdi> · Address: {COMPANY.addressEn}
      </p>
    );
  }
  return (
    <p className={className}>
      الجهة المالكة لحداء: <b>{COMPANY.legalName}</b> ({COMPANY.form}) · السجل التجاري: <bdi>{COMPANY.cr}</bdi> · العنوان: {COMPANY.address}
    </p>
  );
}
