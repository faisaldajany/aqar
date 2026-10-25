# Build phases

Each phase ends with a review checkpoint.

| Phase | Scope | Status |
|---|---|---|
| 0. Foundations | Repo, Spring Boot, Flyway schema, Postgres RLS, tenant-aware DataSource, signup/login (JWT in HttpOnly cookie), portal shell, CI | Done |
| 1. WhatsApp | Webhook verify + HMAC, inbound/status/echo handling, agent pause on human reply, 24-hour window, Cloud API + mock clients, Embedded Signup with coexistence, inbox, dev simulator | Done; needs the real Meta app (go-live §1) |
| 2. Agent | Gemini tool calling (search, qualify, handoff), reply guard (ad-license, unbacked promises), fallback, abuse cap, live-model eval suite | Done; voice notes deferred by decision |
| 3. Listings | Excel/CSV import with column matching (Hijri dates, Arabic numerals), Aqar link import, listing cards (pin, photos, video), ad-license guard, plan limits, usage alerts | Done |
| 4. Bookings | Slot engine, with-marketer vs self-visit, approvals with lock code / guard / key, office message templates, reminders, follow-up | Done |
| 5. Portal | Inbox with search and lead card (temperature), assign to marketer, handoff queue, dashboard KPIs, bookings, listings, settings | Done |
| 6. Self-serve | 14-day trial, plans and annual billing per the pricing doc, Moyasar invoices, overage, renewal grace, daily manager report, activation checklist, FAL review console | Done; needs Moyasar and ZATCA (go-live §3–4) |
| 7. Ops | Production Docker images, compose stack with Caddy TLS, encrypted nightly backups, structured logs with request ids, Sentry, Prometheus, PDPL erasure and retention | Done; needs Saudi hosting (go-live §5) |

What is left is external: [go-live.md](go-live.md).