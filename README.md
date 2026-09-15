# pulse-api

Backend of **Pulse**, the ROGUETECHNOLOGIES insights dashboard: one phone-first screen that shows
how every project (Bookvas, RogueTech, Portfolio, client sites) is doing, alerts Kagiso when
something needs him, and lets him act from his phone. Spring Boot 4 / Java 21 / Postgres / Flyway,
deployed to Cloud Run in `africa-south1`. Frontend: `pulse-web` (Angular 21, Netlify).

Specs: `specs/PULSE-SPEC.md` (product), `specs/PULSE-CONTRACT.md` (HTTP contract — identical copy
in pulse-web), `specs/BOOKVAS-API-GAPS.md` (Bookvas endpoints Pulse still needs).
Package guide: `PACKAGES.md`.

## Run locally

```bash
docker run -d --name pulse-pg -p 55432:5432 -e POSTGRES_DB=pulse -e POSTGRES_USER=pulse -e POSTGRES_PASSWORD=pulse postgres:16
cp application-local.properties.example application-local.properties   # fill in DB_PASSWORD, PULSE_JWT_SECRET, PULSE_ALLOWED_EMAIL, GOOGLE_CLIENT_ID
./mvnw spring-boot:run                                                  # http://localhost:8090
curl -s localhost:8090/actuator/health
curl -s -X POST -H "X-Job-Token: devjob" localhost:8090/internal/jobs/uptime   # with JOBS_TOKEN=devjob
```

Flyway creates the schema (V1) and seeds the four projects, six alert rules and the settings row
(V2) on first start. Every connector is optional: without its env vars it reports "not configured"
in `GET /api/settings` and jobs skip it.

## Test

```bash
DB_PASSWORD=pulse PULSE_JWT_SECRET=<64 hex> PULSE_ALLOWED_EMAIL=test@example.com GOOGLE_CLIENT_ID=test ./mvnw -B verify
```

The context-load test runs Flyway against the real database (`DB_URL`, default
`jdbc:postgresql://localhost:55432/pulse`). CI (`.github/workflows/ci.yml`) does the same with a
`postgres:16` service container.

## Deploy

- `Dockerfile` (multi-stage, non-root) + `cloudbuild.yaml`; triggers in `deploy/cloud-build/`
  (`pulse-api-sit` from `development`, `pulse-api` from `main`).
- `deploy/gcp/bootstrap.sh` — documented, idempotent gcloud commands: APIs, the
  `pulse-api-run@` service account, secrets, the `pulse` database on `bookr-pg`, the Cloud Run
  service (`--set-secrets`, `--add-cloudsql-instances`, `--max-instances=1`) and the Cloud
  Scheduler jobs (`pulse-metrics` */15, `pulse-uptime` */5, `pulse-billing` hourly,
  `pulse-summary` 07:00 SAST, plus `pulse-ga4-discovery` and `pulse-github-poll` hourly).

## KAGISO-only items (nothing else blocks on them)

1. GA4: create the Pulse service-account key, grant it **Viewer at the account level** in
   GA4 Admin → Account access management; paste the JSON into secret `GA4_SA_JSON`.
2. Bookvas: create a super-admin credential for Pulse → `BOOKVAS_SUPER_EMAIL` /
   `BOOKVAS_SUPER_PASSWORD`.
3. Netlify token, GitHub token + webhook secret, Anthropic API key, Brevo SMTP key → secrets.
4. Decide WhatsApp now or email-only (`WHATSAPP_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_TO`);
   without them every WhatsApp rule falls back to email.
5. Google OAuth client id for the frontend → `GOOGLE_CLIENT_ID`; your Google email →
   `PULSE_ALLOWED_EMAIL`.
6. Run `deploy/gcp/bootstrap.sh`, connect the repo in Cloud Build, import the triggers.
7. Supply the prospect CSV (`POST /api/prospects/import`, columns in the contract §2.5).
8. Ask Bookvas for the endpoints in `specs/BOOKVAS-API-GAPS.md`.
