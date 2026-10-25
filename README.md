# Waslah Agent

WhatsApp AI agent for Saudi real-estate brokerage offices, with a self-serve office portal.

| Part | Stack |
|---|---|
| `backend/` | Java 21, Spring Boot 3.4, JDBC + Flyway, PostgreSQL 16 with row-level security |
| `portal/` | Next.js 15 (App Router), TypeScript, Arabic RTL |
| `docker-compose.yml` | Local Postgres |

## Run locally

```bash
docker compose up -d                       # Postgres with the waslah_app role
cd backend && DEV_TOOLS=true mvn spring-boot:run
cd portal && npm install && npm run dev    # http://localhost:3000
```

Defaults run WhatsApp in **mock mode**: nothing is sent to Meta. Sign up in the portal, press
"ربط تجريبي" on the WhatsApp page, then feed a fake customer message:

```bash
curl -X POST localhost:8080/dev/simulate-inbound -H 'Content-Type: application/json' \
  -d '{"phoneNumberId":"mock-pn-<first 8 chars of your office id>","from":"966551234567","name":"أبو عبدالله","text":"أبغى فيلا في النرجس"}'
```

`/dev/*` exists only when `DEV_TOOLS=true`. Never enable it in production.

## Tests

```bash
cd backend && mvn verify   # needs Docker for Testcontainers
```

The integration tests run the app as the non-superuser `waslah_app` role, so they fail if any
query could cross offices. CI runs the same on every push.

## Tenant isolation

Every tenant table has `office_id` and an RLS policy on `app.office_id`. `TenantDataSource` sets
that value on each pooled connection from `TenantContext` and clears it on return. Rules:

- Set `TenantContext` **before** opening a transaction (the connection is bound at transaction start).
- The app role must never be superuser, owner, or `BYPASSRLS`. A test asserts this.
- Tables outside RLS (`users`, `wa_phone_routes`, `webhook_events`) hold no cross-office reads.

## Configuration

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_APP_USER`, `DB_APP_PASSWORD` | Runtime connection (non-superuser) |
| `DB_OWNER_USER`, `DB_OWNER_PASSWORD` | Flyway migrations |
| `JWT_SECRET` | ≥ 32 bytes |
| `TOKEN_ENC_KEY` | Base64 of 32 bytes; encrypts WhatsApp tokens at rest |
| `COOKIE_SECURE` | `true` behind HTTPS |
| `WA_MODE` | `mock` or `cloud` |
| `META_APP_ID`, `META_APP_SECRET`, `META_CONFIG_ID` | Meta app and Embedded Signup configuration |
| `WA_VERIFY_TOKEN` | Webhook subscription check |
| `WA_GRAPH_VERSION` | Graph API version |
| `BACKEND_URL` | Portal → API proxy target (build time) |
| `GEMINI_API_KEY`, `LLM_MODEL` | Agent model (paid tier only) |
| `AGENT_*_TEMPLATE`, `REPORT_TEMPLATE` | Approved WhatsApp templates for messages outside the 24-hour window |
| `BILLING_PROVIDER`, `MOYASAR_SECRET_KEY`, `PORTAL_BASE_URL`, `API_BASE_URL`, `VAT_PERCENT` | Subscription payments |
| `ADMIN_TOKEN` | Operator console (`/admin/*`, FAL review); blank disables it |
| `MESSAGE_RETENTION_DAYS`, `WEBHOOK_RETENTION_DAYS` | PDPL retention |
| `SENTRY_DSN` | Error tracking (optional) |

With `BILLING_PROVIDER=mock` and `DEV_TOOLS=true`, the billing page "pays" with one click.

## Deploy

One VM in a Saudi region: `deploy/docker-compose.prod.yml` (Postgres, API, portal, Caddy with automatic
TLS, nightly encrypted backups). Steps and every external account still needed: [docs/go-live.md](docs/go-live.md).

## Phases

See [docs/phases.md](docs/phases.md). What is left before the first paying office is in
[docs/go-live.md](docs/go-live.md); Meta API details to confirm are in [docs/meta-integration.md](docs/meta-integration.md).
