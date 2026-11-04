/** The legal entity behind Hidaa, as printed on its commercial registration. */
export const COMPANY = {
  legalName: "شركة غين وجذور",
  form: "شركة ذات مسؤولية محدودة",
  cr: "7050828479",
  address: "الرياض، حي الوزارات، شارع أبي تمام الهاشمي",
} as const;

/**
 * Who owns Hidaa, on every public page. Meta's business verification checks that the site shows the legal name
 * (and that its address matches the documents), so keep this in the server-rendered HTML, word for word as on the CR.
 */
export function CompanyLine({ className }: { className?: string }) {
  return (
    <p className={className}>
      الجهة المالكة لحداء: <b>{COMPANY.legalName}</b> ({COMPANY.form}) · السجل التجاري: <bdi>{COMPANY.cr}</bdi> · العنوان: {COMPANY.address}
    </p>
  );
}
