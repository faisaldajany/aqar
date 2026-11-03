# Go-live checklist

Everything in the code is built and tested (CI: unit + integration tests on a real Postgres, portal
typecheck/build, production Docker images). What remains needs accounts, approvals or decisions that
only Hidaa can make. Each item says what blocks on it and where it plugs in.

Legend: **[blocker]** the product cannot take paying offices without it · **[launch]** needed for a
good launch · **[later]** can follow the first paying offices.

## 1. Meta / WhatsApp — [blocker]

Owner: Faisal. Details and API points to verify: [meta-integration.md](meta-integration.md).

- [ ] Meta Business portfolio for Hidaa with **business verification** done (needs CR, website, privacy policy URL).
- [ ] Meta app with WhatsApp, registered as **Tech Provider**; App Review for `whatsapp_business_management`
      and `whatsapp_business_messaging`.
- [ ] Embedded Signup configuration with **WhatsApp Business app onboarding (coexistence)** → `META_CONFIG_ID`.
- [ ] Webhook: `https://app.hidaaco.com/webhooks/whatsapp`, verify token `WA_VERIFY_TOKEN`, fields `messages`,
      `smb_message_echoes`, `smb_app_state_sync`, `history`, `account_update`.
- [ ] Run one real office number end-to-end on staging (`WA_MODE=cloud`) and tick the 7 "confirm" items in meta-integration.md.
- [x] **Message templates**: created automatically on each office's own WhatsApp account when it connects
      (built; see meta-integration.md → Message templates). To do: watch the first office's templates reach
      **معتمد** on its WhatsApp page or in /ops, and adjust the wording if Meta rejects one.

Note: a booking confirmation (including an approved self-visit) sent after the 24-hour window falls back
to the reminder template; if that is not approved yet, the office gets a "confirmation not sent" alert.
A self-visit rejection is free text only, so it reaches the customer only inside the window.

## 2. Gemini — [blocker]

- [ ] Enable **billing** on the Google AI project that owns `GEMINI_API_KEY` (paid tier: prompts are not
      used to improve Google's models; the free tier's are). Free tier also hits 429 rate limits.
- [ ] Decide on data location (see §6 PDPL): the Gemini API is not served from a Saudi region. If
      counsel requires in-Kingdom processing, switch `LLM_PROVIDER` to a Vertex AI endpoint in an
      approved region (the `Llm.Client` interface isolates this).
- [ ] Keep the eval green: Actions → "Agent eval" (report on the `eval-results` branch).

## 3. Payments: Moyasar — [blocker]

- [ ] Open a Moyasar account (CR, bank account), get `sk_test_…`.
- [ ] Staging with `BILLING_PROVIDER=moyasar` and the test key: subscribe, pay with a test card, confirm
      the invoice turns **مدفوعة** and the subscription **فعّال**. The code calls `POST /v1/invoices`
      (`amount`, `currency`, `description`, `success_url`, `back_url`, `callback_url`, `expired_at`,
      `metadata`) and reads `id`, `url`, `status` — confirm these names against Moyasar's API reference.
- [ ] Switch to `sk_live_…` in `MOYASAR_SECRET_KEY`, and keep the test key in `MOYASAR_TEST_SECRET_KEY`: the team's own
      offices (owner email on hidaaco.com, confirmed from the emailed link) keep paying with test cards; every other
      office pays for real.
- [ ] Webhook in Moyasar → Settings → Webhooks, in test mode and again in live mode: URL
      `https://app.hidaaco.com/webhooks/moyasar`, method POST, events `PAYMENT_PAID`, `PAYMENT_FAILED`, `PAYMENT_REFUNDED`, `PAYMENT_VOIDED`, `INVOICE_EXPIRED`, `INVOICE_CANCELED`, `PAYOUT_FAILED`, `PAYOUT_RETURNED`, `PAYOUT_CANCELED`, Secret Token = the
      `moyasar-webhook-secret` secret (`MOYASAR_WEBHOOK_SECRET` on hidaa-api). Each invoice's own callback carries a
      token derived from the same secret, so both kinds of notification are checked.
- [ ] What each event does: paid → invoice paid; failed → the reason shows on the billing page and in /ops; refunded or
      voided → invoice مستردة, refunds come off revenue in the month made, a full refund of a subscription takes its
      months back, the office gets a notice and the team an email; invoice expired/canceled → «ادفع» makes a new page;
      payout failed/returned/canceled → email to the team. Every status is re-checked with Moyasar's API first.
- [ ] Renewal is a new hosted invoice each period (no stored cards). Saved-card auto-charge is a later
      improvement (Moyasar tokenization).

## 4. Tax and invoicing — [blocker for charging]

- [ ] Plans (home page, Oct 2026): البداية 199 / الأساسي 499 / النمو 999 SAR a month for 100 / 500 / 2,000
      conversations; 0.50 SAR per extra conversation (البداية hands new conversations to the office instead).
      The site says prices exclude VAT and the code adds `VAT_PERCENT=15`; set it to 0 if that changes.
- [ ] Growth plan: more than one WhatsApp number per office is not built yet (the page shows 1).
- [ ] VAT registration and **ZATCA e-invoicing (Fatoora)**. Today the portal shows invoice records,
      not ZATCA-compliant tax invoices (no QR/UUID/cryptographic stamp, not reported to ZATCA).
      Options: issue the tax invoice from an accounting system already integrated with Fatoora
      (e.g. Qoyod, Zoho Books, Daftra) triggered on each `PAID` invoice, or integrate directly later.
      Owners already get Hidaa-branded emails (invoice due, payment receipt with card and Moyasar reference,
      refund) and a printable receipt at /receipt/{id}; these say «إيصال دفع», not «فاتورة ضريبية». The cleared
      ZATCA invoice PDF can be attached to the same receipt email once a provider is chosen.

## 5. Hosting in Saudi Arabia — [blocker]

Live: Google Cloud `us-central1` (Dammam needs CNTXT), Cloud Run with scale to zero, IPv4 + IPv6.
Step by step: [deploy-cloud-run.md](deploy-cloud-run.md). Moving to a Saudi region is a PDPL decision (§6).
The single-VM option below (`deploy/docker-compose.prod.yml`) remains as an alternative.

- [ ] Pick a provider with a Saudi region (e.g. Google Cloud Dammam, Oracle Cloud Jeddah/Riyadh,
      Alibaba Cloud Riyadh, STC Cloud). One VM (2 vCPU, 4 GB) is enough for the first ~50 offices.
- [ ] Mail (Spacemail): mailboxes `support@hidaaco.com` (support form forwards there; shown on the site) and
      `no-reply@hidaaco.com` (sender). Set `MAIL_HOST=mail.spacemail.com`, `MAIL_PORT=465`, `MAIL_SSL=true`,
      `MAIL_STARTTLS=false`, `MAIL_USERNAME=no-reply@hidaaco.com`, secret `MAIL_PASSWORD`; add Spacemail's DKIM
      record. **Password reset emails need this**; until then use "رابط كلمة مرور" in /ops. Support messages are
      kept and listed in /ops → رسائل الدعم, and sent automatically once mail works (retried for 3 days).
      Meta verification and Moyasar also ask for an email on the domain; `support@hidaaco.com` or `faisal@hidaaco.com` is enough.
- [ ] DNS `A` record for `DOMAIN` (`app.hidaaco.com`; point `hidaaco.com` at the website) → VM. Caddy issues TLS automatically.
- [ ] Deploy:
      ```bash
      cp deploy/.env.example deploy/.env    # fill in
      docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
      ```
- [ ] **Off-site backups**: the `backup` service writes an encrypted dump nightly at 02:00 to the
      `backups` volume. Add a cron on the VM to copy it to object storage in the same region, and do one
      restore drill: `gpg -d file.sql.gz.gpg | gunzip | psql`.
- [ ] Alerts: `bash deploy/cloudrun/monitoring.sh` (uptime check on hidaaco.com, alert on API 5xx, email to
      support@hidaaco.com).
- [ ] Error tracking: create a Sentry project (EU or self-hosted per §6) and set `SENTRY_DSN`. PII is
      off by default (`send-default-pii: false`).
- [ ] Metrics: `/actuator/prometheus` on the internal port 8081 if you run Prometheus/Grafana.

## 6. PDPL (Saudi Personal Data Protection Law) — [launch]

Built in: tenant isolation by row-level security; WhatsApp tokens encrypted at rest; customer erasure
on request (inbox → بيانات العميل → حذف); chat content deleted after `MESSAGE_RETENTION_DAYS` (730);
raw webhook payloads deleted after `WEBHOOK_RETENTION_DAYS` (30); the agent says it is an assistant.

Needs Hidaa/legal:
- [ ] Privacy policy and terms on the website (also required by Meta). Hidaa is **processor** for the
      offices' customer data; offices are controllers. Put a data-processing agreement in the terms.
- [ ] Cross-border transfer assessment for Gemini (and Sentry if outside KSA), or move them in-region.
- [ ] Check whether registration on SDAIA's National Data Governance Platform applies.
- [ ] Confirm the retention periods with counsel.

## 7. Operations — [launch]

- [x] **Admin console** at https://app.hidaaco.com/ops (admin key from Secret Manager `admin-token`): offices,
      users, FAL review, trial days, free months, plan changes, suspend/reactivate, disable user, password reset
      links, promo codes, support messages, WhatsApp template status, audit log. Scripts can still call
      `/admin/offices` with the `X-Admin-Token` header.
- [ ] **FAL review**: new offices show "فال قيد التحقق" in /ops. Check each number with REGA's
      "الاستعلام عن رخص فال" and record the result on the office page (a rejection with a note alerts the office).
      No public REGA API was found.
- [x] **Revenue and invoices** (/ops → الإيرادات والفواتير): monthly recurring revenue, collected per month, every
      invoice, and «تسجيل دفعة» for bank transfers (the reference goes to the audit log).
- [x] **Usage and cost** (/ops → الاستخدام والتكلفة): conversations, replies, errors and estimated Gemini cost per office
      against what it pays, and the signup funnel with the offices that stopped at each step. Check the token prices
      (`LLM_PRICE_INPUT_USD`, `LLM_PRICE_OUTPUT_USD`) against the model you run.
- [x] **Reply quality** (/ops → مراجعة الردود): every customer conversation is checked by rules (agent errors and
      fallback replies, slow or missing replies, repeated or very long replies, a customer asking for a person without a
      handoff, a complaint); «ابدأ المراجعة» has Gemini grade a sample against the listings the agent's tools returned.
      Open a conversation to read it with the agent's tool calls. Export CSV (Excel) or JSON Lines. Customers are masked
      (a code instead of number and name; phone numbers, emails, IDs and IBANs in messages blanked), and every view,
      review and export is in the audit log.
- [ ] **Terms**: say that Hidaa may review de-identified conversations to improve the service (Hidaa is the offices'
      processor under PDPL; the reviews and exports above rely on this).
- [x] **Promo codes** (/ops → أكواد الخصم): percent, fixed SAR or extra trial days; limits by plan, cycle, dates,
      uses and new customers; signup link `https://hidaaco.com/signup?promo=CODE`.
- [ ] Aqar: a listings feed partnership (portal's "اطلب ربط عقار" requests are stored in
      `aqar_feed_requests`). Until then offices use Excel/CSV or paste a listing link.
- [ ] Home page placeholders to fill when ready: commercial registration / freelance document number, support WhatsApp number, demo WhatsApp number (buttons go to sign-up until then). Privacy policy and terms are drafts: have counsel review them.
- [ ] Support channel (a Hidaa WhatsApp number) shown in the portal footer.

## 8. Security built in

- Email verification: a new account gets one email with a 6-digit code (30 minutes, 5 tries) and a link (48 hours);
  until it confirms, the portal shows only the confirmation screen and the API answers 403 `email_unverified`.
  Accounts made before this count as verified, except hidaaco.com ones (those decide test payments). Needs mail
  (Spacemail) working; turn off with `EMAIL_VERIFICATION_REQUIRED=false` only in an emergency.

- Sign-in, signup, password reset, promo codes and the admin key are rate-limited (in memory; the API runs as one
  instance). Wrong passwords: 10 per email from one address, 50 per email, 50 per address, per 15 minutes, so a
  stranger cannot lock an owner out from one address. IPv6 callers are counted by their /64 network.
- Disabling a user, suspending an office or changing a password ends existing sessions on the next request.
  A suspended office's agent, reminders, follow-ups and daily report stop until it is reactivated.
- Password reset links are one-time (claimed atomically), stored hashed, valid 1 hour (24 hours when created in /ops).
- Admin key: at least 24 characters (setup.sh makes 48). Changing it (`admin-token` secret, then redeploy) ends every
  /ops session. Wrong keys: 5 per 15 minutes per address, for the login form and the `X-Admin-Token` header together.
- Pages send HSTS, no-framing, nosniff, referrer and permissions headers and a minimal CSP. A stricter CSP
  (script sources) can follow once WhatsApp Embedded Signup has been tested end-to-end with it.

## 9. Deliberately not built yet (per the pricing doc)

- Voice notes: the agent replies asking for text. Add transcription after the market test.
- Growth-only features: build when two paying offices ask.
- Optional 1,500 SAR assisted setup: sell manually; `invoices.kind = 'SETUP'` exists for billing it.
