import type { Metadata } from "next";
import Link from "next/link";
import { COMPANY } from "@/components/CompanyLine";
import { LegalPage } from "@/components/LegalPage";

export const metadata: Metadata = { title: "Terms and conditions — Hidaa", alternates: { languages: { ar: "/terms" } } };

/** English translation of /terms. Keep the two in step. */
export default function TermsEn() {
  return (
    <LegalPage title="Terms and conditions" updated="4 November 2026" lang="en" other="/terms">
      <p>
        The Hidaa platform is provided by {COMPANY.legalNameEn} (<bdi lang="ar">{COMPANY.legalName}</bdi>), a {COMPANY.formEn}, Commercial
        Registration <bdi>{COMPANY.cr}</bdi>, referred to in these terms as &ldquo;Hidaa&rdquo;. By using the platform you accept these terms on
        behalf of your office.
      </p>
      <p className="muted small">This is an English translation of the <Link href="/terms">Arabic terms</Link>, which prevail if the two differ.</p>
      <h2>The service</h2>
      <p>Hidaa replies to your office&apos;s customers on WhatsApp using the listing data the office uploads, books viewings and hands customers to marketers. The office is responsible for the accuracy of its listing data, its advertising permits and its FAL licence, and for complying with the regulations of the Real Estate General Authority.</p>
      <h2>Trial and subscription</h2>
      <ul>
        <li>A 14-day free trial with no card, then a monthly subscription for the chosen plan, with no contract.</li>
        <li>Conversations are counted as defined on the pricing page. Anything above the plan&apos;s limit is billed monthly, or handed to the office on the Starter plan.</li>
        <li>Prices are in Saudi riyals and final: Hidaa is not currently registered for VAT, so no VAT is added.</li>
      </ul>
      <h2>Payment and renewal</h2>
      <ul>
        <li>The subscription is paid in advance, monthly or yearly, through a secure payment page at the payment provider. We do not store your card details.</li>
        <li>There is no automatic renewal: you receive a new invoice before the end of each period. If it is not paid within 7 days of the end of the period, the agent stops replying until it is paid.</li>
        <li>The invoice for extra conversations is issued at the start of each month for the previous month.</li>
      </ul>
      <h2>Cancellation and refunds</h2>
      <p>You can cancel at any time at no charge, and the subscription continues until the end of the paid period. Cancellation and refund details are in the <Link href="/en/refund">cancellation and refund policy</Link>.</p>
      <h2>WhatsApp fees</h2>
      <p>Meta&apos;s fees for WhatsApp messages are paid directly from the office&apos;s account, and Hidaa adds nothing on top.</p>
      <h2>Acceptable use</h2>
      <p>Hidaa may not be used to send unsolicited bulk messages, for unlicensed advertising, or in any way that breaches WhatsApp&apos;s policies or Saudi regulations.</p>
      <h2>Limitation of liability</h2>
      <p>The agent is an automated assistant that introduces itself to the customer. It does not negotiate or give legal or financing advice. Decisions on sales, leases and negotiation remain with the office.</p>
      <h2>Account suspension</h2>
      <p>Hidaa may suspend an office&apos;s account if it breaches these terms or WhatsApp&apos;s policies, or if its FAL licence proves invalid or expired, and will tell the office why.</p>
      <h2>Changes to the terms and prices</h2>
      <p>We may change these terms or prices, and will notify you of any material change at least 30 days in advance by email or in the dashboard. A price change does not apply to a period you have already paid for.</p>
      <h2>Governing law</h2>
      <p>These terms are governed by the laws of the Kingdom of Saudi Arabia, and the courts of Riyadh have jurisdiction over any dispute arising from them.</p>
      <h2>Data</h2>
      <p>We process the data of the office and its customers in accordance with the <Link href="/en/privacy">privacy policy</Link>.</p>
    </LegalPage>
  );
}
