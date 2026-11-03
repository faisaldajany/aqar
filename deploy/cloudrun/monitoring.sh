#!/usr/bin/env bash
# Email alerts when Hidaa is down or failing. Run once in Cloud Shell from the repo root, after setup.sh:
#   bash deploy/cloudrun/monitoring.sh                      # alerts go to support@hidaaco.com
#   ALERT_EMAIL=me@example.com bash deploy/cloudrun/monitoring.sh
# Safe to re-run: existing checks, channel and policies are kept.
#
# What it sets up:
#   1. An uptime check on https://hidaaco.com/ every 5 minutes from several regions (DNS, certificate, load balancer,
#      portal). The portal is billed per request, so the checks cost almost nothing.
#   2. An alert when the API answers 5xx more than 5 times in 5 minutes (real traffic: WhatsApp webhooks, portal).
#      The API is deliberately not pinged: it keeps CPU allocated while up, and pings would stop it scaling to zero
#      (about $50 a month). Set CHECK_API=1 to add an API uptime check anyway.
set -uo pipefail

PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null)}"
ALERT_EMAIL="${ALERT_EMAIL:-support@hidaaco.com}"
SITE_HOST="${SITE_HOST:-hidaaco.com}"
API_HOST="${API_HOST:-app.hidaaco.com}"
[ -n "$PROJECT_ID" ] || { echo "Set the project first: gcloud config set project <id>"; exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== Monitoring for $PROJECT_ID, alerts to $ALERT_EMAIL"
gcloud services enable monitoring.googleapis.com --project "$PROJECT_ID" || exit 1

echo "== Email notification channel"
CHANNEL="$(gcloud beta monitoring channels list --project "$PROJECT_ID" \
  --filter="type=\"email\" AND labels.email_address=\"$ALERT_EMAIL\"" --format='value(name)' 2>/dev/null | head -1)"
if [ -z "$CHANNEL" ]; then
  CHANNEL="$(gcloud beta monitoring channels create --project "$PROJECT_ID" --display-name="Hidaa alerts ($ALERT_EMAIL)" \
    --type=email --channel-labels=email_address="$ALERT_EMAIL" --format='value(name)')" || {
      echo "Could not create the channel. If gcloud asks for the beta component: gcloud components install beta"; exit 1; }
fi
echo "   $CHANNEL"

uptime_check() { # display-name host path
  if gcloud monitoring uptime list-configs --project "$PROJECT_ID" --format='value(displayName)' 2>/dev/null | grep -qx "$1"; then
    echo "   uptime check '$1' exists"
    return
  fi
  gcloud monitoring uptime create "$1" --project "$PROJECT_ID" --resource-type=uptime-url \
    --resource-labels="host=$2,project_id=$PROJECT_ID" --protocol=https --path="$3" \
    || echo "   could not create '$1': create it in the console (Monitoring > Uptime checks) for https://$2$3"
}

policy() { # display-name json-file
  if gcloud monitoring policies list --project "$PROJECT_ID" --filter="displayName=\"$1\"" --format='value(name)' 2>/dev/null | grep -q .; then
    echo "   alert policy '$1' exists"
    return
  fi
  gcloud monitoring policies create --project "$PROJECT_ID" --policy-from-file="$2" >/dev/null \
    && echo "   alert policy '$1' created" \
    || echo "   could not create alert policy '$1' (see the error above)"
}

uptime_alert() { # display-name host
  cat > "$TMP/uptime-$2.json" <<JSON
{
  "displayName": "$1",
  "combiner": "OR",
  "conditions": [{
    "displayName": "Uptime check failing for $2",
    "conditionThreshold": {
      "filter": "resource.type = \"uptime_url\" AND metric.type = \"monitoring.googleapis.com/uptime_check/check_passed\" AND resource.label.host = \"$2\"",
      "aggregations": [{
        "alignmentPeriod": "1200s",
        "perSeriesAligner": "ALIGN_NEXT_OLDER",
        "crossSeriesReducer": "REDUCE_COUNT_FALSE",
        "groupByFields": ["resource.label.*"]
      }],
      "comparison": "COMPARISON_GT",
      "thresholdValue": 1,
      "duration": "300s",
      "trigger": {"count": 1}
    }
  }],
  "notificationChannels": ["$CHANNEL"],
  "documentation": {
    "content": "https://$2 failed uptime checks from more than one region for 5 minutes. Check DNS (A and AAAA records), the certificate (gcloud compute ssl-certificates list) and Cloud Run (gcloud run services list).",
    "mimeType": "text/markdown"
  }
}
JSON
  policy "$1" "$TMP/uptime-$2.json"
}

echo "== Uptime checks"
uptime_check "Hidaa site" "$SITE_HOST" "/"
uptime_alert "Hidaa site down" "$SITE_HOST"
if [ "${CHECK_API:-0}" = "1" ]; then
  uptime_check "Hidaa API" "$API_HOST" "/api/public/health"
  uptime_alert "Hidaa API down" "$API_HOST"
fi

echo "== API errors"
cat > "$TMP/api-5xx.json" <<JSON
{
  "displayName": "Hidaa API errors",
  "combiner": "OR",
  "conditions": [{
    "displayName": "hidaa-api answered 5xx more than 5 times in 5 minutes",
    "conditionThreshold": {
      "filter": "resource.type = \"cloud_run_revision\" AND resource.label.service_name = \"hidaa-api\" AND metric.type = \"run.googleapis.com/request_count\" AND metric.label.response_code_class = \"5xx\"",
      "aggregations": [{
        "alignmentPeriod": "300s",
        "perSeriesAligner": "ALIGN_SUM",
        "crossSeriesReducer": "REDUCE_SUM"
      }],
      "comparison": "COMPARISON_GT",
      "thresholdValue": 5,
      "duration": "0s",
      "trigger": {"count": 1}
    }
  }],
  "notificationChannels": ["$CHANNEL"],
  "documentation": {
    "content": "The API returned server errors. Logs: gcloud run services logs read hidaa-api --limit 100 (add --region). Sentry, if SENTRY_DSN is set.",
    "mimeType": "text/markdown"
  }
}
JSON
policy "Hidaa API errors" "$TMP/api-5xx.json"

echo
echo "Done. Alerts to $ALERT_EMAIL come from Google Cloud Monitoring (alerting-noreply@google.com)."
echo "Review in the console: Monitoring > Alerting, and Monitoring > Uptime checks."
