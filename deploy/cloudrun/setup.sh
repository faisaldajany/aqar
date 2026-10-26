#!/usr/bin/env bash
# Hidaa on Google Cloud, scale to zero: Cloud Run (API + portal) + Cloud SQL + HTTPS load balancer + Cloud Scheduler.
# Run in Google Cloud Shell from the repo root:  bash deploy/cloudrun/setup.sh
# Safe to re-run: existing resources are kept, images are rebuilt and redeployed.
# Full walkthrough: docs/deploy-cloud-run.md
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${REGION:-me-central2}"                 # Dammam
SCHEDULER_REGION="${SCHEDULER_REGION:-$REGION}" # if Cloud Scheduler is not offered in me-central2, use europe-west1
DOMAIN="${DOMAIN:-app.hidaaco.com}"
DB_INSTANCE="${DB_INSTANCE:-hidaa-db}"
DB_TIER="${DB_TIER:-db-f1-micro}"
REPO="hidaa"
API="hidaa-api"
PORTAL="hidaa-portal"
SA="hidaa-run"

[ -n "$PROJECT_ID" ] || { echo "Set the project first: gcloud config set project <id>"; exit 1; }
echo "Project $PROJECT_ID · region $REGION · domain $DOMAIN"
gcloud config set project "$PROJECT_ID" >/dev/null
IMG="$REGION-docker.pkg.dev/$PROJECT_ID/$REPO"
SA_EMAIL="$SA@$PROJECT_ID.iam.gserviceaccount.com"

step() { printf '\n== %s\n' "$*"; }
exists() { "$@" >/dev/null 2>&1; }
rand() { openssl rand -hex "${1:-24}"; }

step "1/9 APIs"
gcloud services enable run.googleapis.com sqladmin.googleapis.com artifactregistry.googleapis.com \
  cloudbuild.googleapis.com secretmanager.googleapis.com cloudscheduler.googleapis.com compute.googleapis.com

step "2/9 Secrets (generated once; Gemini key asked once)"
secret() { # name value
  if exists gcloud secrets describe "$1"; then return; fi
  printf '%s' "$2" | gcloud secrets create "$1" --replication-policy=user-managed --locations="$REGION" --data-file=-
}
secret db-owner-password "$(rand 18)"
secret db-app-password "$(rand 18)"
secret jwt-secret "$(rand 32)"
secret token-enc-key "$(openssl rand -base64 32)"
secret jobs-token "$(rand 24)"
secret admin-token "$(rand 24)"
if ! exists gcloud secrets describe gemini-api-key; then
  read -rsp "Gemini API key (paid tier): " GEMINI; echo
  secret gemini-api-key "$GEMINI"
fi
get() { gcloud secrets versions access latest --secret="$1"; }

step "3/9 Service account"
exists gcloud iam service-accounts describe "$SA_EMAIL" || gcloud iam service-accounts create "$SA" --display-name="Hidaa Cloud Run"
for role in roles/cloudsql.client roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$SA_EMAIL" --role="$role" --condition=None >/dev/null
done

step "4/9 Cloud SQL (PostgreSQL 16, takes ~10 minutes the first time)"
if ! exists gcloud sql instances describe "$DB_INSTANCE"; then
  gcloud sql instances create "$DB_INSTANCE" --database-version=POSTGRES_16 --edition=ENTERPRISE --tier="$DB_TIER" \
    --region="$REGION" --storage-size=10 --storage-auto-increase --availability-type=zonal \
    --backup-start-time=23:00 --enable-point-in-time-recovery --retained-backups-count=14
fi
exists gcloud sql databases describe waslah --instance="$DB_INSTANCE" || gcloud sql databases create waslah --instance="$DB_INSTANCE"
if ! gcloud sql users list --instance="$DB_INSTANCE" --format='value(name)' | grep -qx waslah; then
  gcloud sql users create waslah --instance="$DB_INSTANCE" --password="$(get db-owner-password)"
fi
CONN="$(gcloud sql instances describe "$DB_INSTANCE" --format='value(connectionName)')"
JDBC="jdbc:postgresql:///waslah?cloudSqlInstance=$CONN&socketFactory=com.google.cloud.sql.postgres.SocketFactory"

step "5/9 Build images (Cloud Build, ~8 minutes)"
exists gcloud artifacts repositories describe "$REPO" --location="$REGION" || \
  gcloud artifacts repositories create "$REPO" --repository-format=docker --location="$REGION"
TAG="$(git rev-parse --short HEAD 2>/dev/null || date +%s)"
gcloud builds submit backend --tag "$IMG/api:$TAG"
gcloud builds submit portal --tag "$IMG/portal:$TAG"

step "6/9 Deploy API and portal (min instances 0 = scale to zero)"
# CPU stays allocated while an instance is up so agent replies finish after the webhook is acknowledged;
# the instance still shuts down after ~15 idle minutes. One instance until locks move out of memory.
if exists gcloud run services describe "$API" --region "$REGION"; then
  # Already configured: only roll out the new image, so settings changed later (Meta, Moyasar, mail) are kept.
  gcloud run services update "$API" --region "$REGION" --image "$IMG/api:$TAG"
else
gcloud run deploy "$API" --image "$IMG/api:$TAG" --region "$REGION" --service-account "$SA_EMAIL" \
  --add-cloudsql-instances "$CONN" --allow-unauthenticated --ingress internal-and-cloud-load-balancing \
  --min-instances 0 --max-instances 1 --no-cpu-throttling --cpu-boost --cpu 1 --memory 1Gi --timeout 300 \
  --set-env-vars "^|^DB_URL=$JDBC|DB_APP_USER=waslah_app|DB_OWNER_USER=waslah|COOKIE_SECURE=true|DEV_TOOLS=false|JOBS_SCHEDULER=external|WA_MODE=${WA_MODE:-mock}|BILLING_PROVIDER=${BILLING_PROVIDER:-mock}|PORTAL_BASE_URL=https://$DOMAIN|API_BASE_URL=https://$DOMAIN|SUPPORT_FORWARD_TO=faisal@hidaaco.com|SUPPORT_FROM=no-reply@hidaaco.com|SENTRY_ENVIRONMENT=production" \
  --set-secrets "DB_APP_PASSWORD=db-app-password:latest,DB_OWNER_PASSWORD=db-owner-password:latest,JWT_SECRET=jwt-secret:latest,TOKEN_ENC_KEY=token-enc-key:latest,JOBS_TOKEN=jobs-token:latest,ADMIN_TOKEN=admin-token:latest,GEMINI_API_KEY=gemini-api-key:latest"
fi
if exists gcloud run services describe "$PORTAL" --region "$REGION"; then
  gcloud run services update "$PORTAL" --region "$REGION" --image "$IMG/portal:$TAG"
else
  gcloud run deploy "$PORTAL" --image "$IMG/portal:$TAG" --region "$REGION" --service-account "$SA_EMAIL" \
  --allow-unauthenticated --ingress internal-and-cloud-load-balancing --port 3000 \
  --min-instances 0 --max-instances 3 --cpu 1 --memory 512Mi
fi

step "7/9 HTTPS load balancer for $DOMAIN"
exists gcloud compute addresses describe hidaa-ip --global || gcloud compute addresses create hidaa-ip --global
for s in api portal; do
  svc="hidaa-$s"
  exists gcloud compute network-endpoint-groups describe "$svc-neg" --region="$REGION" || \
    gcloud compute network-endpoint-groups create "$svc-neg" --region="$REGION" --network-endpoint-type=serverless --cloud-run-service="$svc"
  if ! exists gcloud compute backend-services describe "$svc-be" --global; then
    gcloud compute backend-services create "$svc-be" --global --load-balancing-scheme=EXTERNAL_MANAGED
    gcloud compute backend-services add-backend "$svc-be" --global --network-endpoint-group="$svc-neg" --network-endpoint-group-region="$REGION"
  fi
done
if ! exists gcloud compute url-maps describe hidaa-lb --global; then
  gcloud compute url-maps create hidaa-lb --global --default-service=hidaa-portal-be
  gcloud compute url-maps add-path-matcher hidaa-lb --global --path-matcher-name=routes \
    --default-service=hidaa-portal-be --new-hosts="$DOMAIN" \
    --backend-service-path-rules="/api/*=hidaa-api-be,/webhooks/*=hidaa-api-be,/admin/*=hidaa-api-be,/internal/*=hidaa-api-be"
fi
exists gcloud compute ssl-certificates describe hidaa-cert --global || \
  gcloud compute ssl-certificates create hidaa-cert --global --domains="$DOMAIN"
exists gcloud compute target-https-proxies describe hidaa-https --global || \
  gcloud compute target-https-proxies create hidaa-https --url-map=hidaa-lb --ssl-certificates=hidaa-cert --global
exists gcloud compute forwarding-rules describe hidaa-443 --global || \
  gcloud compute forwarding-rules create hidaa-443 --global --load-balancing-scheme=EXTERNAL_MANAGED \
    --address=hidaa-ip --target-https-proxy=hidaa-https --ports=443
# http:// -> https://
if ! exists gcloud compute url-maps describe hidaa-redirect --global; then
  gcloud compute url-maps import hidaa-redirect --global --quiet --source /dev/stdin <<YAML
name: hidaa-redirect
defaultUrlRedirect:
  httpsRedirect: true
  redirectResponseCode: MOVED_PERMANENTLY_DEFAULT
YAML
fi
exists gcloud compute target-http-proxies describe hidaa-http --global || \
  gcloud compute target-http-proxies create hidaa-http --url-map=hidaa-redirect --global
exists gcloud compute forwarding-rules describe hidaa-80 --global || \
  gcloud compute forwarding-rules create hidaa-80 --global --load-balancing-scheme=EXTERNAL_MANAGED \
    --address=hidaa-ip --target-http-proxy=hidaa-http --ports=80
IP="$(gcloud compute addresses describe hidaa-ip --global --format='value(address)')"

step "8/9 Cloud Scheduler jobs (Asia/Riyadh)"
JOB_TOKEN="$(get jobs-token)"
job() { # name schedule
  local args=(--location="$SCHEDULER_REGION" --schedule="$2" --time-zone="Asia/Riyadh" --http-method=POST
    --uri="https://$DOMAIN/internal/jobs/$1" --update-headers="X-Job-Token=$JOB_TOKEN" --attempt-deadline=300s)
  if exists gcloud scheduler jobs describe "hidaa-$1" --location="$SCHEDULER_REGION"; then
    gcloud scheduler jobs update http "hidaa-$1" "${args[@]}" >/dev/null
  else
    gcloud scheduler jobs create http "hidaa-$1" "${args[@]/--update-headers/--headers}" >/dev/null
  fi
  echo "  hidaa-$1  $2"
}
job bookings "*/5 * * * *"
job support-retry "*/10 * * * *"
job daily-report "0 * * * *"
job retention "30 3 * * *"
job license-guard "0 6 * * *"
job billing "0 7 * * *"

step "9/9 Done"
cat <<EOF

Load balancer IP: $IP

Now, at your domain registrar, add:   A   app   $IP
The HTTPS certificate is issued automatically 15-60 minutes after the record is visible.
Check:  gcloud compute ssl-certificates describe hidaa-cert --global --format='value(managed.status)'
Then open https://$DOMAIN/signup

Operator token (for /admin/*):  gcloud secrets versions access latest --secret=admin-token
EOF
