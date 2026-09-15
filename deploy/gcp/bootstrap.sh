#!/usr/bin/env bash
# pulse-api GCP bootstrap (PULSE-SPEC.md §8). Documented, idempotent gcloud commands — run by
# Kagiso from a machine with gcloud, NEVER from CI. Every step is safe to re-run.
#
# Usage:  PROJECT=bookings-prod-503907 GOOGLE_CLIENT_ID=... MAIL_TO=... SPRING_MAIL_USERNAME=... \
#           ./deploy/gcp/bootstrap.sh [prod|sit]
# Prereqs: gcloud auth login; gcloud config set project $PROJECT; the bookr-pg Cloud SQL instance
# and the cloud-run-source-deploy Artifact Registry repo already exist (bookr-api created them).
#
# 2026-09-15 corrections after the first live walkthrough:
#   * Cloud Scheduler has no africa-south1 location — jobs live in SCHEDULER_LOCATION
#     (default europe-west1); the HTTP target stays the africa-south1 Cloud Run URL.
#   * The first deploy builds from source (--source .), because no pulse-api image exists until
#     Cloud Build has run once. Later image updates come from deploy/cloud-build triggers.
#   * Only secrets that exist are mounted: the Bookvas login reuses PULSE_BOOKVAS_PASSWORD (the value
#     bookr-api seeds pulse@rogue-tech.co.za with); optional connectors (GitHub token, WhatsApp,
#     Anthropic) are mounted only when their secret exists — the app degrades to "not configured".
#   * Email-only alerting for now: SPRING_MAIL_USERNAME must be passed (the Brevo login bookr-api uses).
set -euo pipefail

PROJECT="${PROJECT:-bookings-prod-503907}"
REGION="${REGION:-africa-south1}"
SCHEDULER_LOCATION="${SCHEDULER_LOCATION:-europe-west1}"
ENV="${1:-prod}"                                 # prod (default: the one Pulse) | sit
SERVICE="pulse-api"; [ "$ENV" = "sit" ] && SERVICE="pulse-api-sit"
DB_NAME="pulse"; [ "$ENV" = "sit" ] && DB_NAME="pulse_sit"
SQL_INSTANCE="bookr-pg"
SQL_CONNECTION="$PROJECT:$REGION:$SQL_INSTANCE"
RUN_SA="pulse-api-run@$PROJECT.iam.gserviceaccount.com"
SCHEDULER_SA="pulse-scheduler@$PROJECT.iam.gserviceaccount.com"
WEB_ORIGIN="${WEB_ORIGIN:-https://pulse-web.netlify.app}"
# Bookvas API Pulse reads from: SIT until production is promoted (flip with --update-env-vars).
BOOKVAS_API_BASE_URL="${BOOKVAS_API_BASE_URL:-https://bookr-api-sit-rx2kzgepoa-bq.a.run.app}"
BOOKVAS_SUPER_EMAIL="${BOOKVAS_SUPER_EMAIL:-pulse@rogue-tech.co.za}"
ALLOWED_EMAIL="${ALLOWED_EMAIL:-hadebekagiso3@gmail.com}"

say() { printf '\n== %s\n' "$*"; }
have_secret() { gcloud secrets describe "$1" --project "$PROJECT" >/dev/null 2>&1; }

# ---------------------------------------------------------------------------------------------
say "1. APIs"
gcloud services enable run.googleapis.com cloudscheduler.googleapis.com secretmanager.googleapis.com \
  sqladmin.googleapis.com analyticsdata.googleapis.com analyticsadmin.googleapis.com \
  bigquery.googleapis.com cloudbilling.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com --project "$PROJECT"

# ---------------------------------------------------------------------------------------------
say "2. Runtime service account (least privilege, spec §8)"
gcloud iam service-accounts describe "$RUN_SA" --project "$PROJECT" >/dev/null 2>&1 || \
  gcloud iam service-accounts create pulse-api-run --display-name "pulse-api runtime" --project "$PROJECT"
for ROLE in roles/cloudsql.client roles/run.viewer roles/billing.viewer roles/bigquery.jobUser roles/bigquery.dataViewer; do
  gcloud projects add-iam-policy-binding "$PROJECT" --member "serviceAccount:$RUN_SA" --role "$ROLE" --quiet >/dev/null
done
# the minimum to create a revision on bookr-api (restart action): developer on that ONE service
gcloud run services add-iam-policy-binding bookr-api --region "$REGION" --project "$PROJECT" \
  --member "serviceAccount:$RUN_SA" --role roles/run.developer --quiet >/dev/null || true

say "2b. Cloud Scheduler caller SA (OIDC tokens for /internal/jobs/*)"
gcloud iam service-accounts describe "$SCHEDULER_SA" --project "$PROJECT" >/dev/null 2>&1 || \
  gcloud iam service-accounts create pulse-scheduler --display-name "pulse Cloud Scheduler caller" --project "$PROJECT"

# ---------------------------------------------------------------------------------------------
say "3. Secrets"
# Created here with placeholder values when missing — replace with:
#   printf %s "value" | gcloud secrets versions add NAME --data-file=-
for S in PULSE_JWT_SECRET PULSE_ALLOWED_EMAIL PULSE_DB_PASSWORD; do
  if ! have_secret "$S"; then
    gcloud secrets create "$S" --replication-policy user-managed --locations "$REGION" --project "$PROJECT"
    printf CHANGE-ME | gcloud secrets versions add "$S" --data-file=- --project "$PROJECT"
  fi
done
echo "PULSE_JWT_SECRET suggestion: openssl rand -hex 32 ; PULSE_ALLOWED_EMAIL: $ALLOWED_EMAIL"
# Accessor on every secret the service mounts (existing ones included).
REQUIRED_SECRETS=(PULSE_JWT_SECRET PULSE_ALLOWED_EMAIL PULSE_DB_PASSWORD GA4_SA_JSON PULSE_BOOKVAS_PASSWORD \
                  NETLIFY_TOKEN GITHUB_WEBHOOK_SECRET BREVO_SMTP_KEY)
OPTIONAL_SECRETS=(GITHUB_TOKEN WHATSAPP_TOKEN ANTHROPIC_API_KEY)
for S in "${REQUIRED_SECRETS[@]}"; do
  have_secret "$S" || { echo "Missing required secret $S — create it first"; exit 1; }
  gcloud secrets add-iam-policy-binding "$S" --member "serviceAccount:$RUN_SA" --role roles/secretmanager.secretAccessor \
    --project "$PROJECT" --quiet >/dev/null
done
SECRET_MOUNTS="DB_PASSWORD=PULSE_DB_PASSWORD:latest,PULSE_JWT_SECRET=PULSE_JWT_SECRET:latest,PULSE_ALLOWED_EMAIL=PULSE_ALLOWED_EMAIL:latest,GA4_SA_JSON=GA4_SA_JSON:latest,BOOKVAS_SUPER_PASSWORD=PULSE_BOOKVAS_PASSWORD:latest,NETLIFY_TOKEN=NETLIFY_TOKEN:latest,GITHUB_WEBHOOK_SECRET=GITHUB_WEBHOOK_SECRET:latest,SPRING_MAIL_PASSWORD=BREVO_SMTP_KEY:latest"
for S in "${OPTIONAL_SECRETS[@]}"; do
  if have_secret "$S"; then
    gcloud secrets add-iam-policy-binding "$S" --member "serviceAccount:$RUN_SA" --role roles/secretmanager.secretAccessor \
      --project "$PROJECT" --quiet >/dev/null
    SECRET_MOUNTS="$SECRET_MOUNTS,$S=$S:latest"
  else
    echo "Optional secret $S not present — its connector stays 'not configured'"
  fi
done

# ---------------------------------------------------------------------------------------------
say "4. Database $DB_NAME on $SQL_INSTANCE (+ user pulse)"
gcloud sql databases describe "$DB_NAME" --instance "$SQL_INSTANCE" --project "$PROJECT" >/dev/null 2>&1 || \
  gcloud sql databases create "$DB_NAME" --instance "$SQL_INSTANCE" --project "$PROJECT"
if ! gcloud sql users list --instance "$SQL_INSTANCE" --project "$PROJECT" --format 'value(name)' | grep -qx pulse; then
  echo "Creating SQL user 'pulse' — use the SAME value you stored in secret PULSE_DB_PASSWORD:"
  gcloud sql users create pulse --instance "$SQL_INSTANCE" --project "$PROJECT" --prompt-for-password
fi

# ---------------------------------------------------------------------------------------------
say "5. Cloud Run service $SERVICE — first deploy builds from source (run from the repo root)"
: "${GOOGLE_CLIENT_ID:?set GOOGLE_CLIENT_ID (the public OAuth Web client id)}"
: "${MAIL_TO:?set MAIL_TO (where alerts and the 07:00 summary go)}"
: "${SPRING_MAIL_USERNAME:?set SPRING_MAIL_USERNAME (the Brevo SMTP login bookr-api uses)}"
# '^|^' makes '|' the list separator so the JDBC URL may contain commas/ampersands.
gcloud run deploy "$SERVICE" \
  --source . \
  --region "$REGION" --platform managed --project "$PROJECT" \
  --service-account "$RUN_SA" \
  --add-cloudsql-instances "$SQL_CONNECTION" \
  --max-instances=1 --min-instances=0 --memory 1Gi --cpu 1 --timeout 300 \
  --allow-unauthenticated \
  --set-env-vars "^|^DB_URL=jdbc:postgresql:///$DB_NAME?cloudSqlInstance=$SQL_CONNECTION&socketFactory=com.google.cloud.sql.postgres.SocketFactory|DB_USER=pulse|GOOGLE_CLIENT_ID=$GOOGLE_CLIENT_ID|PULSE_API_BASE_URL=https://placeholder.invalid|SCHEDULER_SA_EMAIL=$SCHEDULER_SA|CORS_ALLOWED_ORIGINS=$WEB_ORIGIN|PULSE_WEB_BASE_URL=$WEB_ORIGIN|GA4_ACCOUNT_NAME=ROGUETECHNOLOGIES|BOOKVAS_API_BASE_URL=$BOOKVAS_API_BASE_URL|BOOKVAS_SUPER_EMAIL=$BOOKVAS_SUPER_EMAIL|GCP_PROJECT_ID=$PROJECT|GCP_REGION=$REGION|SPRING_MAIL_HOST=smtp-relay.brevo.com|SPRING_MAIL_PORT=587|SPRING_MAIL_USERNAME=$SPRING_MAIL_USERNAME|MAIL_FROM=Pulse <pulse@rogue-tech.co.za>|MAIL_TO=$MAIL_TO" \
  --set-secrets "$SECRET_MOUNTS" \
  --quiet
SERVICE_URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --project "$PROJECT" --format 'value(status.url)')
# the OIDC audience the app checks on /internal/jobs/* must equal the real URL
gcloud run services update "$SERVICE" --region "$REGION" --project "$PROJECT" \
  --update-env-vars "PULSE_API_BASE_URL=$SERVICE_URL" --quiet
echo "Service URL: $SERVICE_URL"
echo "Later, when known: --update-env-vars BILLING_EXPORT_TABLE=..., WHATSAPP_PHONE_NUMBER_ID=..., WHATSAPP_TO=..., CORS_ALLOWED_ORIGINS/PULSE_WEB_BASE_URL=<Netlify URL>"

# ---------------------------------------------------------------------------------------------
say "6. Cloud Scheduler jobs in $SCHEDULER_LOCATION (OIDC as $SCHEDULER_SA, audience = service URL)"
gcloud run services add-iam-policy-binding "$SERVICE" --region "$REGION" --project "$PROJECT" \
  --member "serviceAccount:$SCHEDULER_SA" --role roles/run.invoker --quiet >/dev/null
job() { # name schedule path
  local NAME="$1-$ENV" SCHEDULE="$2" URI="$SERVICE_URL$3"
  local VERB=create
  gcloud scheduler jobs describe "$NAME" --location "$SCHEDULER_LOCATION" --project "$PROJECT" >/dev/null 2>&1 && VERB=update
  gcloud scheduler jobs "$VERB" http "$NAME" --location "$SCHEDULER_LOCATION" --project "$PROJECT" --schedule "$SCHEDULE" \
    --time-zone Africa/Johannesburg --uri "$URI" --http-method POST \
    --oidc-service-account-email "$SCHEDULER_SA" --oidc-token-audience "$SERVICE_URL" --attempt-deadline 300s --quiet
}
job pulse-metrics        "*/15 * * * *" /internal/jobs/metrics
job pulse-uptime         "*/5 * * * *"  /internal/jobs/uptime
job pulse-billing        "7 * * * *"    /internal/jobs/billing
job pulse-summary        "0 7 * * *"    /internal/jobs/summary
job pulse-ga4-discovery  "23 * * * *"   /internal/jobs/ga4-discovery
job pulse-github-poll    "41 * * * *"   /internal/jobs/github-poll

say "Done. Next: deploy/cloud-build/README.md (triggers), then the Netlify site and CORS/PULSE_WEB_BASE_URL."
