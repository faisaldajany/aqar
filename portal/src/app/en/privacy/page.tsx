import type { Metadata } from "next";
import Link from "next/link";
import { LegalPage } from "@/components/LegalPage";

export const metadata: Metadata = { title: "Privacy policy — Hidaa", alternates: { languages: { ar: "/privacy" } } };

/** English translation of /privacy. Keep the two in step. */
export default function PrivacyEn() {
  return (
    <LegalPage title="Privacy policy" updated="9 November 2026" lang="en" other="/privacy">
      <p>This policy explains how the Hidaa platform handles data, in line with the Personal Data Protection Law of the Kingdom of Saudi Arabia.</p>
      <p className="muted small">This is an English translation of the <Link href="/privacy">Arabic policy</Link>, which prevails if the two differ.</p>
      <h2>Who we are and our role</h2>
      <p>Hidaa is a platform that gives real-estate brokerage offices an automated agent that replies to their customers on WhatsApp. The real-estate office is responsible for its customers&apos; data, and Hidaa processes it on the office&apos;s behalf and only to provide the service.</p>
      <h2>The data we process</h2>
      <ul>
        <li>Office data: name, FAL licence number, user details, the office&apos;s WhatsApp number, listings and marketers.</li>
        <li>Data of the office&apos;s customers: the name shown on WhatsApp, mobile number, messages, the customer&apos;s request (purpose, district, budget, payment method) and appointments.</li>
        <li>Billing data: invoices and payment status. We do not store card details; payment happens on the payment provider&apos;s page.</li>
      </ul>
      <h2>How we use it</h2>
      <ul>
        <li>Replying to the office&apos;s customers, suggesting its listings, booking viewings, and handing the customer to a marketer.</li>
        <li>Showing conversations and reports to the office.</li>
        <li>We do not sell data or use it for advertising, and we never use one office&apos;s conversations to serve another office.</li>
      </ul>
      <h2>Who processes data with us</h2>
      <ul>
        <li>Meta (WhatsApp): to send and receive messages.</li>
        <li>Google Cloud: to host the platform and its database.</li>
        <li>Google (Gemini): to generate the agent&apos;s replies from customers&apos; messages.</li>
        <li>The payment provider: to process subscriptions.</li>
        <li>Hidaa&apos;s commission-based marketers: if an office subscribes with a marketer&apos;s code, the marketer sees the office&apos;s name, plan, subscription status, renewal date and the amounts paid with the code, for as long as the office uses the code. They do not see the office&apos;s customers, conversations or contact details.</li>
      </ul>
      <p>Some data may be processed outside the Kingdom by these providers, with appropriate safeguards.</p>
      <h2>Security and retention</h2>
      <ul>
        <li>Each office&apos;s data is isolated from every other office, and access tokens are encrypted.</li>
        <li>Conversation texts are deleted after two years, and the office can delete any customer&apos;s data whenever asked.</li>
      </ul>
      <h2>Data subjects&apos; rights, including deletion</h2>
      <p>A customer of an office can ask to access, correct or delete their data through the office, or by writing to us at <a href="mailto:support@hidaaco.com">support@hidaaco.com</a>; we will pass the request to the office concerned. An office can ask us to delete all of its data the same way.</p>
    </LegalPage>
  );
}
