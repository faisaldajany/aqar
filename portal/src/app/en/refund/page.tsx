import type { Metadata } from "next";
import Link from "next/link";
import { LegalPage } from "@/components/LegalPage";

export const metadata: Metadata = { title: "Cancellation and refund policy — Hidaa", alternates: { languages: { ar: "/refund" } } };

/** English translation of /refund. Keep the two in step. */
export default function RefundEn() {
  return (
    <LegalPage title="Cancellation and refund policy" updated="2 November 2026" lang="en" other="/refund">
      <p>Our aim is that you try Hidaa before you pay, and can cancel whenever you want, with no contract and no cancellation fee.</p>
      <p className="muted small">This is an English translation of the <Link href="/refund">Arabic policy</Link>, which prevails if the two differ.</p>

      <h2>Free trial</h2>
      <ul>
        <li>A 14-day free trial with no card, so nothing is charged before you decide to subscribe.</li>
        <li>If you don&apos;t subscribe by the end of the trial, the agent stops replying and you owe nothing.</li>
      </ul>

      <h2>Cancellation</h2>
      <ul>
        <li>There is no automatic renewal and we don&apos;t store your card details. Each period you receive a new invoice that you pay yourself.</li>
        <li>To cancel, simply don&apos;t pay the renewal invoice, or contact us and we&apos;ll stop it right away.</li>
        <li>The agent keeps working until the end of the paid period, then stops after a 7-day grace period if the renewal invoice isn&apos;t paid.</li>
        <li>You can come back at any time by paying a new invoice; your listings and settings stay as they were.</li>
        <li>You can ask us to delete all of your office&apos;s data by contacting us, in line with the <Link href="/en/privacy">privacy policy</Link>.</li>
      </ul>

      <h2>Refunds</h2>
      <ul>
        <li><b>Monthly subscription:</b> a month&apos;s payment is not refunded once paid, and the subscription continues to the end of the month.</li>
        <li><b>Yearly subscription:</b> refunded in full if you ask within 14 days of paying. After that it is not refunded, and the subscription continues to the end of the year.</li>
        <li><b>Extra conversations:</b> not refunded, unless they were counted in error, in which case we refund the difference.</li>
        <li><b>Duplicate payment or an invoice error:</b> the excess is refunded in full.</li>
        <li><b>Service outage on our side:</b> if the agent stops because of a fault on our side for more than 72 consecutive hours, we give you free days or refund those days, as you choose.</li>
        <li><b>WhatsApp fees:</b> you pay these to Meta directly from your office&apos;s account, so Hidaa cannot refund them.</li>
      </ul>

      <h2>How to request a refund</h2>
      <ol>
        <li>Contact us through the <Link href="/contact">contact form</Link> or at <a href="mailto:support@hidaaco.com">support@hidaaco.com</a>.</li>
        <li>Include the office name, the invoice number and the reason for the request.</li>
        <li>We reply within two working days. Once approved, the amount is returned to the same payment method within 5 to 14 working days, depending on the bank.</li>
      </ol>

      <p>Refunds are made the way the payment was made, and you receive an email confirming the refund. This policy applies together with the <Link href="/en/terms">terms and conditions</Link>.</p>
    </LegalPage>
  );
}
