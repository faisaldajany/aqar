# Deploy on Google Cloud with scale to zero

Cloud Run runs the API and the portal and scales both to zero when idle. Cloud SQL (PostgreSQL) and the
HTTPS load balancer always run; together they are the fixed monthly cost (roughly $30–50, region `me-central2`
uses Google's higher Tier 2 prices). Everything stays in the Dammam region except the load balancer's
global front end.

```
app.hidaaco.com ──► HTTPS load balancer ──┬─ /api/* /webhooks/* /admin/* /internal/* ─► Cloud Run hidaa-api ─► Cloud SQL
                                          └─ everything else ───────────────────────► Cloud Run hidaa-portal
Cloud Scheduler ──► https://app.hidaaco.com/internal/jobs/* (reminders, daily report, billing, retention, templates)
```

The script follows the region the services already run in (the live setup is `us-central1`); a different
`REGION` is refused, since it would build a second copy. A new project defaults to `me-central2`.

## 1. Google Cloud project (console, 5 minutes)

1. Go to https://console.cloud.google.com → project picker → **New project**, e.g. `hidaa-prod`.
2. **Billing** → link the project to your billing account (the same one as Gemini is fine).

## 2. Run the setup script (Cloud Shell, ~25 minutes, mostly waiting)

1. In the console, click **Activate Cloud Shell** (the `>_` icon, top right).
2. Paste:
   ```bash
   gcloud config set project hidaa-prod
   git clone https://github.com/faisaldajany/aqar.git && cd aqar
   bash deploy/cloudrun/setup.sh
   ```
   The repo is private, so `git clone` asks for your GitHub username and a **personal access token**
   (GitHub → Settings → Developer settings → Personal access tokens → fine-grained, read access to `aqar`).
3. When asked, paste your **Gemini API key**. It goes into Secret Manager, never into the code.

The script enables the APIs, creates the secrets (database passwords, session keys, job and admin tokens),
the database with daily backups and point-in-time recovery, builds both images, deploys them with
minimum instances 0, sets up the load balancer with a Google-managed certificate and http→https redirect,
and creates the seven scheduled jobs. At the end it prints the load balancer's **IPv4 and IPv6** addresses.

## 3. DNS (your domain registrar, 2 minutes)

Add these records (Spaceship → hidaaco.com → DNS):

| Type | Name | Value |
|---|---|---|
| A | `app`, `@`, `www` | the IPv4 address the script printed |
| AAAA | `app`, `@`, `www` | the IPv6 address the script printed |

Keep both. Without AAAA, phones on IPv6-only networks (common on Saudi mobile data) reach the site through the
carrier's NAT64, which can time out. Remove any parking or default records the registrar added.

The certificate is issued 15–60 minutes after the record is visible. Check with:
```bash
gcloud compute ssl-certificates describe hidaa-cert --global --format='value(managed.status)'
```
When it says `ACTIVE`, open https://app.hidaaco.com/signup.

## 4. Check it works

- Sign up an office, add a listing, open **الاشتراك** and **الإعدادات**.
- Open https://app.hidaaco.com/contact and send a test message, then see it in the admin console:
  https://app.hidaaco.com/ops (key: `gcloud secrets versions access latest --secret=admin-token`) → **رسائل الدعم**.
- https://app.hidaaco.com/api/public/health answers `{"ok":true}`.
- Cloud Run → `hidaa-api` → **Logs** shows `Job bookings finished` every 5 minutes.
- Leave it idle 15+ minutes: Cloud Run → **Metrics** → instance count drops to 0.

## 5. Turn on the real services (when each account is ready)

Change settings with `gcloud run services update` (below) or in the console (Cloud Run → `hidaa-api` →
**Edit & deploy new revision** → Variables & Secrets). Re-running the script never overwrites them.
Put keys in Secret Manager, not plain variables.

| When | Set on `hidaa-api` |
|---|---|
| Meta app approved | `WA_MODE=cloud`, `META_APP_ID`, `META_CONFIG_ID`, `WA_VERIFY_TOKEN`, secrets `META_APP_SECRET`, `WA_REGISTRATION_PIN`; webhook URL in Meta: `https://app.hidaaco.com/webhooks/whatsapp` |
| Moyasar account | `BILLING_PROVIDER=moyasar`, secret `MOYASAR_SECRET_KEY` |
| Email (Spacemail) | `MAIL_HOST=mail.spacemail.com`, `MAIL_PORT=465`, `MAIL_SSL=true`, `MAIL_STARTTLS=false`, `MAIL_USERNAME=no-reply@hidaaco.com`, secret `MAIL_PASSWORD`. The sender (`SUPPORT_FROM`, default `no-reply@hidaaco.com`) must be that mailbox. Needed for password reset emails and support forwarding |
| Error tracking | `SENTRY_DSN` |

Example for one secret:
```bash
printf '%s' 'sk_test_...' | gcloud secrets create moyasar-secret-key --replication-policy=automatic --data-file=-
gcloud run services update hidaa-api --region us-central1 \
  --update-secrets MOYASAR_SECRET_KEY=moyasar-secret-key:latest --update-env-vars BILLING_PROVIDER=moyasar
```

WhatsApp templates need no settings: Hidaa creates its four templates on each office's own WhatsApp account
when the office connects, and tracks Meta's review (WhatsApp page and /ops). The old `AGENT_*_TEMPLATE` and
`REPORT_TEMPLATE` variables still work as a fallback for Hidaa's own number.

## Alerts

Once, after the first deploy:
```bash
bash deploy/cloudrun/monitoring.sh            # emails support@hidaaco.com; ALERT_EMAIL=... to change
```
It adds an uptime check on https://hidaaco.com (every few minutes, from several regions) and an alert when the
API returns server errors. The API itself is not pinged: it keeps its CPU while running, so pings would keep it
from scaling to zero (about $50 a month). `CHECK_API=1` adds the API check anyway.

## 6. Deploy a new version

```bash
cd aqar && git pull && bash deploy/cloudrun/setup.sh
```
Existing resources are kept; only the images are rebuilt and redeployed. Database migrations run on startup.

## Good to know

- **Cold start**: the first message after an idle period waits ~5–10 s while the API starts (startup CPU
  boost is on). If that matters during busy hours, set `--min-instances 1` on `hidaa-api` (~$25–40/month more).
- **One API instance** (`--max-instances 1`) for now: message debouncing and per-conversation locks are in
  memory. One instance handles many offices; raise it after those move to the database.
- **CPU always allocated** on the API so an agent reply finishes after WhatsApp's webhook is acknowledged.
  Billing is per instance-second while an instance is up; it still shuts down when idle.
- **Backups**: Cloud SQL daily backups (14 kept) and point-in-time recovery. Restore from Cloud SQL → Backups.
- **Data location**: the live setup runs in `us-central1` (Dammam needs a CNTXT account); a fresh project defaults
  to `me-central2`, where Cloud Run, Cloud SQL and images stay in Dammam. Secret Manager does not offer `me-central2`, so the secrets (keys and passwords, no customer data) use automatic replication. The global load balancer
  terminates TLS at Google's nearest edge; for strict in-Kingdom termination, switch to a regional external
  load balancer in `me-central2` later (needs a proxy-only subnet and a Certificate Manager certificate).
- **Cloud Scheduler region**: if `me-central2` is not offered, run `SCHEDULER_REGION=europe-west1 bash deploy/cloudrun/setup.sh`
  (it only sends the timed HTTP calls; no data is stored there).
- **If a build fails with a permission error**: grant the Cloud Build service account
  `roles/artifactregistry.writer` and `roles/logging.logWriter` in IAM, then re-run.
