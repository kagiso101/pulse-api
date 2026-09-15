# PULSE — BUILD REPORT (foundation run)

**Date:** 2026-09-15
**Repos:** `C:\Projects\Personal\BE\pulse-api` (Spring Boot 4.0.5 / Java 21) · `C:\Projects\Personal\FE\pulse-web` (Angular 21)
**Scope:** every feature in `PULSE-SPEC.md` is coded and wired to the contract in `PULSE-CONTRACT.md`. Nothing has run against a live third-party API — no credentials exist on the build machine. Both repos were built and verified locally only; nothing is pushed or deployed.

---

## 1. Verification results

| Check | pulse-api | pulse-web |
|---|---|---|
| Build | `./mvnw -B verify` → BUILD SUCCESS | `npm run build` (production) → no errors, initial bundle 370 kB raw / 105 kB transfer |
| Tests | 36 passed, 0 failed (10 classes) | 65 passed, 0 failed (8 files, vitest) |
| Schema | Flyway V1 + V2 applied to a fresh Postgres 16; `ddl-auto=validate` passes | — |
| Smoke | app started, `/actuator/health` UP, `/api/projects` 401, `/internal/jobs/uptime` wrote 5 `uptime_check` rows against the real public sites, `/internal/jobs/metrics` reported unconfigured connectors as skipped | — |
| Secrets | none in source; all read from env | `npm run check:secrets` → 0 hits in `dist/`; no secret names in `src/` |
| SIT build | — | `npm run build:sit` → OK |

Local verification database: Docker container `pulse-pg`, `jdbc:postgresql://localhost:55432/pulse` (user/password `pulse`). Local API port default is **8090** (8081 is bookr-api, 8082 is taken by another service on the dev box).

## 2. What exists

**pulse-api** — 128 main + 10 test classes under `pulse_api`: `config`, `security` (Google ID-token verify, JWT, scheduler OIDC, rate limit, GitHub HMAC, request id), 15 controllers covering every contract endpoint, 12 connector classes (GA4 Data, GA4 Admin discovery, Bookvas, uptime, Netlify, Cloud Run, GitHub poll, BigQuery billing), jobs (`/internal/jobs/*`, optional in-process scheduler), alert engine + email/WhatsApp notifiers, Ask (Anthropic Java SDK, SSE), services, 16 entities / 17 repositories. Ops: Dockerfile, cloudbuild.yaml, Cloud Build triggers, `deploy/gcp/bootstrap.sh`, GitHub Actions CI, dependabot, `PACKAGES.md`, `README.md`, `specs/BOOKVAS-API-GAPS.md`.

**pulse-web** — 234 source files: shell (project pills, range toggle, refresh indicator, notices bell, bottom nav, Ask bar), 9 pages (login, all, project, prospects, costs, deploys, settings, client-view, not-found), 13 SignalStores in the Bookvas events/reducer/effects/facade/store layout, 14 API services (Ask uses fetch + SSE), 16 shared components (big-number, sparkline, project-card, confirm-sheet, traffic-chart, funnel-bars, ranked-list, deploy-list, revenue-card, founder-countdown, tenant-leaderboard, email-health-card, platform-events, trend-bars, status-dot, toasts), contract models, dark Bookvas CI tokens (`--pl-*`), PWA (manifest, service worker, generated icons), `netlify.toml` with strict CSP, CI with secrets check.

## 3. Deviations from the spec / contract

- `Connector.fetch` returns a `FetchResult` and writes through an upsert `SnapshotWriter`; `metric_snapshot` gained `period_start DATE` and `period_hour` with a unique index so re-runs never duplicate.
- Extra tables/columns beyond the spec's minimum: `alert_state`, `bookvas_platform_event`, `bookvas_tenant_cache`, `notice`, `app_setting`, `project.ga4_measurement_id` (the spec only knows `G-…` ids; the discovery job fills the numeric property id by matching a property's web stream `measurementId`), `project.client_view_token_hash`, `alert_event.dedupe_key/channel/acknowledged_at`, `deploy_event.state`.
- Site-down alerts always email as well as WhatsApp (spec: "WhatsApp + email"); the rule row stores one channel.
- Ask model default is `claude-opus-5` (`ANTHROPIC_MODEL` overrides).
- Frontend: reducers use the correct `(event, state)` signature (bookr-client's reducers spread the event by mistake — not replicated); a brighter `--pl-green-text #4CB08C` exists because `#0E5A45` on the dark background fails contrast as text; Google Fonts are inlined at build time by Angular's font optimisation; component-style budget raised to 10/16 kB.
- Frontend "Add note" and delete prompts use `window.prompt`/`window.confirm`, not the confirm sheet.

## 4. Bookvas API gaps (hard rule 3 — report, don't build here)

See `BOOKVAS-API-GAPS.md`. In short: no platform-wide bookings count, no platform-wide payments/deposits sum, no revenue endpoint; only `booking_started` of the four funnel events is emitted by `bookr-client`. Pulse returns `null` for these and the UI shows "needs Bookvas endpoint"; the funnel shows a stale note.

## 5. Needs Kagiso before anything is live

1. GA4: service-account key → `GA4_SA_JSON`; grant it **account-level Viewer**; `GA4_ACCOUNT_NAME`.
2. Bookvas super-admin credential for Pulse → `BOOKVAS_SUPER_EMAIL` / `BOOKVAS_SUPER_PASSWORD`, `BOOKVAS_API_BASE_URL`.
3. Google OAuth **Web** client → `GOOGLE_CLIENT_ID` (API) and `googleClientId` in the four `environment*.ts` files; authorised origins `http://localhost:4200`, the Netlify URL, later `pulse.rogue-tech.co.za`. `PULSE_ALLOWED_EMAIL`, `PULSE_JWT_SECRET` (`openssl rand -hex 32`).
4. `NETLIFY_TOKEN` + each project's `netlifySiteId`, `GITHUB_TOKEN`, `GITHUB_WEBHOOK_SECRET` (+ webhooks on the repos → `/api/webhooks/github`), `ANTHROPIC_API_KEY`, Brevo `SPRING_MAIL_*` + `MAIL_TO`, WhatsApp (`WHATSAPP_TOKEN/PHONE_NUMBER_ID/TO`) or stay email-only.
5. GCP: run `deploy/gcp/bootstrap.sh` step by step (SA, secrets, `pulse` database on `bookr-pg`, Cloud Scheduler jobs, Cloud Run deploy), enable BigQuery billing export → `BILLING_EXPORT_TABLE`, connect the repo in Cloud Build and import the triggers.
6. GitHub: create `pulse-api` and `pulse-web`, push `main` + `development`, protect `main`, check billing is unlocked for Actions.
7. Netlify: site `pulse-web` from `main`, branch deploys for `development`; then `CORS_ALLOWED_ORIGINS` on the API.
8. Prospect CSV → `POST /api/prospects/import` (or the Settings/Prospects import button).

## 6. Untested paths

Google libraries (GA4 Data/Admin, Cloud Run v2, BigQuery), the Anthropic stream, Netlify, GitHub and Meta clients compile against pinned SDKs but were never called live. Positive-path Google token verification (sign-in and Cloud Scheduler OIDC) needs real tokens. The frontend has not been rendered in a browser (no `ng serve` run): layouts at 390/768/1120 px, the Google button, SSE streaming and the service worker are verified by type-check and unit tests only.
