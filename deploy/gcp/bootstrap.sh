#!/usr/bin/env bash
# pulse-api GCP bootstrap (PULSE-SPEC.md §8). Documented, idempotent gcloud commands — run by
# Kagiso from a machine with gcloud, NEVER from CI. Every step is safe to re-run.
#
# Usage:  PROJECT=bookings-prod-503907 ./deploy/gcp/bootstrap.sh [sit|prod]
# Prereqs: gcloud auth login; gcloud config set project $PROJECT; the bookr-pg Cloud SQL instance
# and the cloud-run-source-deploy Artifact Registry repo already exist (bookr-api created them).
set -euo pipefail

PROJECT="${PROJECT:-bookings-prod-503907}"
REGION="${REGION:-africa-south1}"
ENV="${1:-sit}"                                  # sit | prod
SERVICE="pulse-api"; [ "$ENV" = "sit" ] && SERVICE="pulse-api-sit"
DB_NAME="pulse"; [ "$ENV" = "sit" ] && DB_NAME="pulse_sit"
SQL_INSTANCE="bookr-pg"
SQL_CONNECTION="$PROJECT:$REGION:$SQL_INSTANCE"
RUN_SA="pulse-api-run@$PROJECT.iam.gserviceaccount.com"
SCHEDULER_SA="pulse-scheduler@$PROJECT.iam.gserviceaccount.com"
AR_IMAGE="$REGION-docker.pkg.dev/$PROJECT/cloud-run-source-deploy/pulse-api:latest"
WEB_ORIGIN="${WEB_ORIGIN:-https://pulse-web.netlify.app}"

say() { printf '\n== %s\n' "$*"; }

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
say "3. Secrets (placeholders — replace values with: printf '%s' 'value' | gcloud secrets versions add NAME --data-file=-)"
SECRETS=(PULSE_JWT_SECRET PULSE_ALLOWED_EMAIL PULSE_DB_PASSWORD GA4_SA_JSON BOOKVAS_SUPER_EMAIL BOOKVAS_SUPER_PASSWORD \
         NETLIFY_TOKEN GITHUB_TOKEN GITHUB_WEBHOOK_SECRET BREVO_SMTP_KEY WHATSAPP_TOKEN ANTHROPIC_API_KEY)
for S in "${SECRETS[@]}"; do
  if ! gcloud secrets describe "$S" --project "$PROJECT" >/dev/null 2>&1; then
    gcloud secrets create "$S" --replication-policy user-managed --locations "$REGION" --project "$PROJECT"
    printf 'CHANGE-ME' | gcloud secrets versions add "$S" --data-file=- --project "$PROJECT"
  fi
  gcloud secrets add-iam-policy-binding "$S" --member "serviceAccount:$RUN_SA" --role roles/secretmanager.secretAccessor \
    --project "$PROJECT" --quiet >/dev/null
done
echo "PULSE_JWT_SECRET suggestion: openssl rand -hex 32"

# ---------------------------------------------------------------------------------------------
say "4. Database $DB_NAME on $SQL_INSTANCE (+ user pulse)"
gcloud sql databases describe "$DB_NAME" --instance "$SQL_INSTANCE" --project "$PROJECT" >/dev/null 2>&1 || \
  gcloud sql databases create "$DB_NAME" --instance "$SQL_INSTANCE" --project "$PROJECT"
if ! gcloud sql users list --instance "$SQL_INSTANCE" --project "$PROJECT" --format 'value(name)' | grep -qx pulse; then
  echo "Creating SQL user 'pulse' — set its password now (also store it in secret PULSE_DB_PASSWORD):"
  gcloud sql users create pulse --instance "$SQL_INSTANCE" --project "$PROJECT" --prompt-for-password
fi

# ---------------------------------------------------------------------------------------------
say "5. Cloud Run service $SERVICE (first deploy; Cloud Build takes over image updates afterwards)"
SERVICE_URL_PLACEHOLDER="https://$SERVICE-898880840502.$REGION.run.app"
gcloud run deploy "$SERVICE" \
  --image "$AR_IMAGE" \
  --region "$REGION" --platform managed --project "$PROJECT" \
  --service-account "$RUN_SA" \
  --add-cloudsql-instances "$SQL_CONNECTION" \
  --max-instances=1 --min-instances=0 --memory 1Gi --cpu 1 --timeout 300 \
  --allow-unauthenticated \
  --set-env-vars "DB_URL=jdbc:postgresql:///$DB_NAME?cloudSqlInstance=$SQL_CONNECTION&socketFactory=com.google.cloud.sql.postgres.SocketFactory,DB_USER=pulse,GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID:-CHANGE-ME},PULSE_API_BASE_URL=$SERVICE_URL_PLACEHOLDER,SCHEDULER_SA_EMAIL=$SCHEDULER_SA,CORS_ALLOWED_ORIGINS=$WEB_ORIGIN,PULSE_WEB_BASE_URL=$WEB_ORIGIN,GA4_ACCOUNT_NAME=ROGUETECHNOLOGIES,BOOKVAS_API_BASE_URL=https://bookr-api-898880840502.$REGION.run.app,GCP_PROJECT_ID=$PROJECT,GCP_REGION=$REGION,SPRING_MAIL_HOST=smtp-relay.brevo.com,SPRING_MAIL_PORT=587,MAIL_FROM=Pulse <pulse@rogue-tech.co.za>,MAIL_TO=${MAIL_TO:-CHANGE-ME}" \
  --set-secrets "DB_PASSWORD=PULSE_DB_PASSWORD:latest,PULSE_JWT_SECRET=PULSE_JWT_SECRET:latest,PULSE_ALLOWED_EMAIL=PULSE_ALLOWED_EMAIL:latest,GA4_SA_JSON=GA4_SA_JSON:latest,BOOKVAS_SUPER_EMAIL=BOOKVAS_SUPER_EMAIL:latest,BOOKVAS_SUPER_PASSWORD=BOOKVAS_SUPER_PASSWORD:latest,NETLIFY_TOKEN=NETLIFY_TOKEN:latest,GITHUB_TOKEN=GITHUB_TOKEN:latest,GITHUB_WEBHOOK_SECRET=GITHUB_WEBHOOK_SECRET:latest,SPRING_MAIL_PASSWORD=BREVO_SMTP_KEY:latest,WHATSAPP_TOKEN=WHATSAPP_TOKEN:latest,ANTHROPIC_API_KEY=ANTHROPIC_API_KEY:latest" \
  --quiet
SERVICE_URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --project "$PROJECT" --format 'value(status.url)')
gcloud run services update "$SERVICE" --region "$REGION" --project "$PROJECT" \
  --update-env-vars "PULSE_API_BASE_URL=$SERVICE_URL" --quiet
echo "Service URL: $SERVICE_URL  (set SPRING_MAIL_USERNAME, WHATSAPP_PHONE_NUMBER_ID, WHATSAPP_TO, BILLING_EXPORT_TABLE with --update-env-vars when known)"

# ---------------------------------------------------------------------------------------------
say "6. Cloud Scheduler jobs (OIDC as $SCHEDULER_SA, audience = service URL)"
gcloud run services add-iam-policy-binding "$SERVICE" --region "$REGION" --project "$PROJECT" \
  --member "serviceAccount:$SCHEDULER_SA" --role roles/run.invoker --quiet >/dev/null
job() { # name schedule path
  local NAME="$1-$ENV" SCHEDULE="$2" URI="$SERVICE_URL$3"
  if gcloud scheduler jobs describe "$NAME" --location "$REGION" --project "$PROJECT" >/dev/null 2>&1; then
    gcloud scheduler jobs update http "$NAME" --location "$REGION" --project "$PROJECT" --schedule "$SCHEDULE" \
      --time-zone Africa/Johannesburg --uri "$URI" --http-method POST \
      --oidc-service-account-email "$SCHEDULER_SA" --oidc-token-audience "$SERVICE_URL" --attempt-deadline 300s --quiet
  else
    gcloud scheduler jobs create http "$NAME" --location "$REGION" --project "$PROJECT" --schedule "$SCHEDULE" \
      --time-zone Africa/Johannesburg --uri "$URI" --http-method POST \
      --oidc-service-account-email "$SCHEDULER_SA" --oidc-token-audience "$SERVICE_URL" --attempt-deadline 300s --quiet
  fi
}
job pulse-metrics        "*/15 * * * *" /internal/jobs/metrics
job pulse-uptime         "*/5 * * * *"  /internal/jobs/uptime
job pulse-billing        "7 * * * *"    /internal/jobs/billing
job pulse-summary        "0 7 * * *"    /internal/jobs/summary
job pulse-ga4-discovery  "23 * * * *"   /internal/jobs/ga4-discovery
job pulse-github-poll    "41 * * * *"   /internal/jobs/github-poll

say "Done. Next: deploy/cloud-build/README.md (triggers), then replace every CHANGE-ME secret version."
