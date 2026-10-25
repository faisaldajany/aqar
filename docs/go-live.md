# Go-live checklist

Everything in the code is built and tested (CI: unit + integration tests on a real Postgres, portal
typecheck/build, production Docker images). What remains needs accounts, approvals or decisions that
only Waslah can make. Each item says what blocks on it and where it plugs in.

Legend: **[blocker]** the product cannot take paying offices without it · **[launch]** needed for a
good launch · **[later]** can follow the first paying offices.

## 1. Meta / WhatsApp — [blocker]

Owner: Faisal. Details and API points to verify: [meta-integration.md](meta-integration.md).

- [ ] Meta Business portfolio for Waslah with **business verification** done (needs CR, website, privacy policy URL).
- [ ] Meta app with WhatsApp, registered as **Tech Provider**; App Review for `whatsapp_business_management`
      and `whatsapp_business_messaging`.
- [ ] Embedded Signup configuration with **WhatsApp Business app onboarding (coexistence)** → `META_CONFIG_ID`.
- [ ] Webhook: `https://<DOMAIN>/webhooks/whatsapp`, verify token `WA_VERIFY_TOKEN`, fields `messages`,
      `smb_message_echoes`, `smb_app_state_sync`.
- [ ] Run one real office number end-to-end on staging (`WA_MODE=cloud`) and tick the 7 "confirm" items in meta-integration.md.
- [ ] **Message templates** (category Utility, language `ar`). Until approved, the matching message is
      kept in the portal and the office is alerted instead. Parameters must be in this order:

| Env var | Sent to | Suggested body | Params |
|---|---|---|---|
| `AGENT_HANDOFF_TEMPLATE` | marketer | عميل جديد يحتاجك: {{1}} — {{2}} افتح المحادثة في واتساب المكتب. | customer, 3-line summary |
| `AGENT_REMINDER_TEMPLATE` | customer | أهلاً {{1}}، تذكير بموعد معاينة {{2}} {{3}}. للتغيير رد على هذه الرسالة. | name, listing, day+time |
| `AGENT_FOLLOWUP_TEMPLATE` | customer | أهلاً {{1}}، كيف كانت زيارتك لـ{{2}}؟ يسعدنا نساعدك في الخطوة الجاية. | name, listing |
| `REPORT_TEMPLATE` | office owner | تقرير {{1}} ليوم {{2}}: استفسارات {{3}}، عملاء جادون {{4}}، مواعيد حُجزت {{5}}، مواعيد الغد {{6}}، تحويلات {{7}}، رسائل بعد الدوام {{8}}. | office, date, 6 numbers |

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
- [ ] Switch to `sk_live_…`.
- [ ] Renewal is a new hosted invoice each period (no stored cards). Saved-card auto-charge is a later
      improvement (Moyasar tokenization).

## 4. Tax and invoicing — [blocker for charging]

- [ ] Confirm the pricing doc's prices exclude VAT (the code adds `VAT_PERCENT=15`; set it to 0 and
      adjust prices if they are VAT-inclusive).
- [ ] VAT registration and **ZATCA e-invoicing (Fatoora)**. Today the portal shows invoice records,
      not ZATCA-compliant tax invoices (no QR/UUID/cryptographic stamp, not reported to ZATCA).
      Options: issue the tax invoice from an accounting system already integrated with Fatoora
      (e.g. Qoyod, Zoho Books, Daftra) triggered on each `PAID` invoice, or integrate directly later.

## 5. Hosting in Saudi Arabia — [blocker]

- [ ] Pick a provider with a Saudi region (e.g. Google Cloud Dammam, Oracle Cloud Jeddah/Riyadh,
      Alibaba Cloud Riyadh, STC Cloud). One VM (2 vCPU, 4 GB) is enough for the first ~50 offices.
- [ ] DNS `A` record for `DOMAIN` (e.g. `app.waslah.sa`) → VM. Caddy issues TLS automatically.
- [ ] Deploy:
      ```bash
      cp deploy/.env.example deploy/.env    # fill in
      docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
      ```
- [ ] **Off-site backups**: the `backup` service writes an encrypted dump nightly at 02:00 to the
      `backups` volume. Add a cron on the VM to copy it to object storage in the same region, and do one
      restore drill: `gpg -d file.sql.gz.gpg | gunzip | psql`.
- [ ] Uptime check on `https://<DOMAIN>/` and alerting on container restarts.
- [ ] Error tracking: create a Sentry project (EU or self-hosted per §6) and set `SENTRY_DSN`. PII is
      off by default (`send-default-pii: false`).
- [ ] Metrics: `/actuator/prometheus` on the internal port 8081 if you run Prometheus/Grafana.

## 6. PDPL (Saudi Personal Data Protection Law) — [launch]

Built in: tenant isolation by row-level security; WhatsApp tokens encrypted at rest; customer erasure
on request (inbox → بيانات العميل → حذف); chat content deleted after `MESSAGE_RETENTION_DAYS` (730);
raw webhook payloads deleted after `WEBHOOK_RETENTION_DAYS` (30); the agent says it is an assistant.

Needs Waslah/legal:
- [ ] Privacy policy and terms on the website (also required by Meta). Waslah is **processor** for the
      offices' customer data; offices are controllers. Put a data-processing agreement in the terms.
- [ ] Cross-border transfer assessment for Gemini (and Sentry if outside KSA), or move them in-region.
- [ ] Check whether registration on SDAIA's National Data Governance Platform applies.
- [ ] Confirm the retention periods with counsel.

## 7. Operations — [launch]

- [ ] **FAL review**: new offices show "قيد التحقق". Check each number against REGA's broker register
      and record the result:
      ```bash
      curl -H "X-Admin-Token: $ADMIN_TOKEN" https://<DOMAIN>/admin/offices
      curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -d '{"status":"VERIFIED"}' https://<DOMAIN>/admin/offices/<office-id>/fal
      ```
      (REJECTED with a `note` alerts the office.) No public REGA API was found; if one becomes
      available, automate it in `AdminController`.
- [ ] Aqar: a listings feed partnership (portal's "اطلب ربط عقار" requests are stored in
      `aqar_feed_requests`). Until then offices use Excel/CSV or paste a listing link.
- [ ] Onboarding website: pricing updated to 499 / 1,000 SAR, no setup fee. Point its sign-up button to `https://<DOMAIN>/signup`.
- [ ] Support channel (a Waslah WhatsApp number) shown in the portal footer.

## 8. Deliberately not built yet (per the pricing doc)

- Voice notes: the agent replies asking for text. Add transcription after the market test.
- Growth-only features: build when two paying offices ask.
- Optional 1,500 SAR assisted setup: sell manually; `invoices.kind = 'SETUP'` exists for billing it.
