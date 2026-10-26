# Deploy on Google Cloud with scale to zero

Cloud Run runs the API and the portal and scales both to zero when idle. Cloud SQL (PostgreSQL) and the
HTTPS load balancer always run; together they are the fixed monthly cost (roughly $30–50, region `me-central2`
uses Google's higher Tier 2 prices). Everything stays in the Dammam region except the load balancer's
global front end.

```
app.hidaaco.com ──► HTTPS load balancer ──┬─ /api/* /webhooks/* /admin/* /internal/* ─► Cloud Run hidaa-api ─► Cloud SQL
                                          └─ everything else ───────────────────────► Cloud Run hidaa-portal
Cloud Scheduler ──► https://app.hidaaco.com/internal/jobs/* (reminders, daily report, billing, retention)
```

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
and creates the six scheduled jobs. At the end it prints the load balancer **IP**.

## 3. DNS (your domain registrar, 2 minutes)

Add one record:

| Type | Name | Value |
|---|---|---|
| A | `app` | the IP the script printed |

The certificate is issued 15–60 minutes after the record is visible. Check with:
```bash
gcloud compute ssl-certificates describe hidaa-cert --global --format='value(managed.status)'
```
When it says `ACTIVE`, open https://app.hidaaco.com/signup.

## 4. Check it works

- Sign up an office, add a listing, open **الاشتراك** and **الإعدادات**.
- Open https://app.hidaaco.com/contact and send a test message; list it with
  `curl -H "X-Admin-Token: $(gcloud secrets versions access latest --secret=admin-token)" https://app.hidaaco.com/admin/support`
- Cloud Run → `hidaa-api` → **Logs** shows `Job bookings finished` every 5 minutes.
- Leave it idle 15+ minutes: Cloud Run → **Metrics** → instance count drops to 0.

## 5. Turn on the real services (when each account is ready)

Change settings with `gcloud run services update` (below) or in the console (Cloud Run → `hidaa-api` →
**Edit & deploy new revision** → Variables & Secrets). Re-running the script never overwrites them.
Put keys in Secret Manager, not plain variables.

| When | Set on `hidaa-api` |
|---|---|
| Meta app approved | `WA_MODE=cloud`, `META_APP_ID`, `META_CONFIG_ID`, `WA_VERIFY_TOKEN`, secrets `META_APP_SECRET`, `WA_REGISTRATION_PIN`; webhook URL in Meta: `https://app.hidaaco.com/webhooks/whatsapp` |
| Templates approved | `AGENT_HANDOFF_TEMPLATE`, `AGENT_REMINDER_TEMPLATE`, `AGENT_FOLLOWUP_TEMPLATE`, `REPORT_TEMPLATE` |
| Moyasar account | `BILLING_PROVIDER=moyasar`, secret `MOYASAR_SECRET_KEY` |
| Email provider | `MAIL_HOST`, `MAIL_USERNAME`, secret `MAIL_PASSWORD` |
| Error tracking | `SENTRY_DSN` |

Example for one secret:
```bash
printf '%s' 'sk_test_...' | gcloud secrets create moyasar-secret-key --replication-policy=user-managed --locations=me-central2 --data-file=-
gcloud run services update hidaa-api --region me-central2 \
  --update-secrets MOYASAR_SECRET_KEY=moyasar-secret-key:latest --update-env-vars BILLING_PROVIDER=moyasar
```

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
- **Data location**: Cloud Run, Cloud SQL, secrets and images are in `me-central2`. The global load balancer
  terminates TLS at Google's nearest edge; for strict in-Kingdom termination, switch to a regional external
  load balancer in `me-central2` later (needs a proxy-only subnet and a Certificate Manager certificate).
- **Cloud Scheduler region**: if `me-central2` is not offered, run `SCHEDULER_REGION=europe-west1 bash deploy/cloudrun/setup.sh`
  (it only sends the timed HTTP calls; no data is stored there).
- **If a build fails with a permission error**: grant the Cloud Build service account
  `roles/artifactregistry.writer` and `roles/logging.logWriter` in IAM, then re-run.
