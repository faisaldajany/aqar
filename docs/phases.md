# Build phases

Each phase ends with a review checkpoint.

| Phase | Scope | Status |
|---|---|---|
| 0. Foundations | Repo, Spring Boot, Flyway schema, Postgres RLS, tenant-aware DataSource, signup/login (JWT in HttpOnly cookie), portal shell, CI | Done, pending CI |
| 1. WhatsApp | Webhook verify + HMAC, inbound/status/echo handling, agent pause on human reply, 24-hour window, Cloud API + mock clients, Embedded Signup with coexistence, conversation APIs, inbox, dev simulator | Done, pending CI and real Meta app |
| 2. Agent | LLM with tool calling (search listings, qualify, book, hand off), voice-note transcription, guardrails, evaluation set | Next |
| 3. Listings | Excel/CSV import with column matching, Aqar link import, ad-license expiry job | |
| 4. Bookings | Slot engine, rep vs self-visit, approvals, templates, reminders, follow-up | |
| 5. Portal | Wire the prototype screens: listings, bookings, settings, lead cards | |
| 6. Self-serve | FAL verification, payments, daily report, activation flow | |
| 7. Ops | Tracing, error tracking, Saudi-region deployment, backups, PDPL | |
