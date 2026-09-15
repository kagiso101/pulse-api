# pulse-api — Package Guide

Backend of Pulse, the ROGUETECHNOLOGIES insights dashboard (PULSE-SPEC.md). Connectors pull
numbers from GA4, Bookvas, uptime probes, Netlify, Cloud Run, GitHub and the GCP billing export
into Postgres snapshots; the API serves those snapshots (never live sources) to `pulse-web`; an
alert engine and a 07:00 summary tell Kagiso when something needs him. Spring Boot 4 / Java 21 /
PostgreSQL / Flyway / JWT.

This file is the single place for package-level documentation — keep code comments in the
source minimal and put explanations here.

## Running locally

- Requires PostgreSQL on `localhost:55432`, database `pulse`, user `pulse` (the `pulse-pg` Docker
  container — see README). Port 55432 avoids bookr-api's own databases on 5432/5434.
- `./mvnw spring-boot:run` — starts on port **8090** (SERVER_PORT > PORT > 8090; 8081 is bookr-api,
  8080 is Apache on the dev box).
- **Schema is Flyway-authoritative from V1** (spec hard rule 6): `ddl-auto=validate`, so Hibernate
  never creates or patches tables. `V1__pulse_schema.sql` is the full schema, `V2__seed.sql` the
  four registry rows, six alert rules and the settings row. Never edit a shipped migration; add
  `V3+`.
- Config is env-var driven with `${ENV:default}` placeholders (PULSE-CONTRACT.md §6). Secrets have
  **no dev defaults** — copy `application-local.properties.example` to
  `application-local.properties` (gitignored). In production the same names come from Secret
  Manager (`deploy/gcp/bootstrap.sh`).
- Trigger jobs locally with `JOBS_TOKEN=devjob` and `curl -X POST -H "X-Job-Token: devjob"
  localhost:8090/internal/jobs/<job>`; or set `PULSE_INPROCESS_SCHEDULING=true` for timers.
- Swagger UI: http://localhost:8090/swagger-ui.html — off unless `API_DOCS_ENABLED=true`.

## Packages

### `pulse_api` (root)
`PulseApiApplication` — entry point, `@EnableScheduling` (timers only exist when
`InProcessScheduler` is enabled).

### `config`
- `SecurityConfig` — the authorization table (contract §0.1/§4): public = `POST /api/auth/google`,
  `GET /api/public/**`, `POST /api/webhooks/github`, `/actuator/health`, swagger;
  `/internal/**` = ROLE_SCHEDULER; everything else under `/api/**` = ROLE_OWNER (the Pulse JWT).
  Stateless, CSP `default-src 'none'; frame-ancestors 'none'`, HSTS, no-referrer, JSON 401/403
  via `JsonAuthHandlers`, `UserDetailsService` stub so no generated password is logged.
  Filter order: RateLimit → SchedulerAuth → JwtAuth.
- `CorsConfig` — single CORS source from `app.cors.allowed-origins` (`CORS_ALLOWED_ORIGINS`).
- `AppConfig` — the shared `Clock` (tests pin "now"), a lenient Jackson 2 mapper for upstream
  payloads, the `RestClient` connectors use, and the virtual-thread executor Ask streams on.

### `security`
- `JwtService` — HS256, 12h, claims `sub=email`, `role=OWNER`; fails fast at startup when
  `PULSE_JWT_SECRET` is missing or under 32 bytes (the value is never echoed).
- `JwtAuthenticationFilter` — `Authorization: Bearer <pulse JWT>` → `ROLE_OWNER`; invalid tokens
  simply stay unauthenticated (→ 401 from the rules).
- `SchedulerAuthFilter` — `/internal/**` only: Google OIDC token with `aud == PULSE_API_BASE_URL`
  and `email == SCHEDULER_SA_EMAIL`, or `X-Job-Token == JOBS_TOKEN` (constant-time; local dev).
- `IdTokenVerifier` / `GoogleIdTokenVerifier` — `com.google.auth.oauth2.TokenVerifier` against
  Google's certs (signature, expiry, audience) plus an issuer check. Interface so tests can mock.
- `RateLimitFilter` — fixed window per client IP: auth 10/min, actions 20/min, ask 10/min,
  public client view 60/min. In-memory, hence `--max-instances=1` on Cloud Run.
- `ClientIpResolver` — which `X-Forwarded-For` entry is the real client (Cloud Run appends it;
  `app.rate-limit.trusted-proxy-hops`, default 1).
- `GithubSignatureVerifier` — `X-Hub-Signature-256` HMAC-SHA256, constant-time compare.
- `RequestIdFilter` — request id on the MDC for every log line (`X-Request-Id` / Cloud Trace).
- `CurrentUser` — the owner's email from the JWT subject (for `action_log.actor_email`).

### `controller`
Thin HTTP layer, exact contract paths:

| Route | Auth | Notes |
|---|---|---|
| `POST /api/auth/google`, `GET /api/auth/me` | public / JWT | `GoogleAuthController` |
| `/api/projects` CRUD, `/{id}/client-view-token`, `/{slug}/dashboard` | JWT | `ProjectController` |
| `GET /api/overview?range=` | JWT | `OverviewController` |
| `/api/alerts/rules`, `/api/alerts/events`, `/events/{id}/ack` | JWT | `AlertController` |
| `/api/prospects` CRUD, `PATCH status` / `next-action`, `POST notes`, `POST import` (multipart) | JWT | `ProspectController` |
| `GET /api/costs?month=`, `PUT /api/costs/manual` | JWT | `CostController` |
| `GET /api/deploys?projectId=&limit=` | JWT | `DeployController` |
| `GET /api/summary/latest`, `GET /api/summary?limit=` | JWT | `SummaryController` |
| `POST /api/actions/**`, `GET /api/actions/log` | JWT, 20/min | `ActionController` — body `{confirm:true}` |
| `POST /api/ask` | JWT, 10/min | `AskController` — `text/event-stream` |
| `GET/PUT /api/settings` | JWT | `SettingsController` |
| `GET /api/notices?unread=`, `POST /api/notices/{id}/read` | JWT | `NoticeController` |
| `GET /api/public/client-view/{token}` | none, 60/min | `PublicClientViewController` — 404 on miss |
| `POST /internal/jobs/{job}` | Cloud Scheduler | `InternalJobController` |
| `POST /api/webhooks/github` | HMAC | `GithubWebhookController` |

### `service`
- `RangeResolver` — `today|7d|30d` → Africa/Johannesburg day boundaries (`ResolvedRange`).
- `SnapshotWriter` — the only writer of `metric_snapshot`: an upsert on the unique
  (project, source, metric, dimension, period, period_start) index, so 15-minute re-runs converge.
- `MetricQueryService` — typed sums/series/top-N over snapshots; the metric key vocabulary
  (`active_users`, `sessions`, `page_views`, `page_views:path`, `sessions:source`, `event:<name>`,
  Bookvas keys such as `tenants_total`, `founder_seats_used`, `email_failed_24h`).
- `UptimeStatusService` — up/down/unknown from `uptime_check` (down = 3 consecutive failures),
  uptime %, latest latency.
- `ProjectService` (registry CRUD, soft delete), `OverviewService` (the "All" view: six headline
  numbers, cards with exactly 3 numbers + sparkline, Needs-you), `DashboardService` (per-project
  view; Bookvas section incl. funnel/drop-offs, tenant leaderboard, email health, events),
  `ClientViewService` + `ClientViewTokenService` (32 random bytes, base64url, SHA-256 at rest),
  `ProspectService` + `ProspectCsvParser` (RFC 4180-ish, header aliases, dedupe by name+phone),
  `CostService` (manual upsert, 6-month trend), `DeployService` (timeline + idempotent upsert),
  `ActionService` (confirm required → 400; `action_log` written before, updated after; upstream
  failures are `result:"failed"`, never 500), `SettingsService`, `NoticeService`, `SummaryService`,
  `AuthService` (Google ID token → allow-listed email → JWT; 403 `NOT_ALLOWED`),
  `GithubWebhookService` (push / deployment_status → deploy_event).

### `connector`
`Connector { source(); configured(); fetch(FetchWindow) }` — one class per upstream, dumb
fetchers; every one degrades to `configured()==false` without its env vars and any exception is
caught by `JobRunner` and listed in the job result. `partOfMetricsJob()` decides whether the
15-minute `metrics` job runs it.

- `Ga4DataConnector` — GA4 Data API per property/day: activeUsers, sessions, screenPageViews,
  top-10 pagePath, sessionSource, tracked events (`file_download, click, scroll, booking_started,
  slot_selected, deposit_initiated, purchase`). First pull backfills 30 days.
- `GoogleCredentialsFactory` — the GA4 service account from `GA4_SA_JSON` (Data + Admin scopes).
- `BookvasClient` / `BookvasConnector` — super-admin login with in-memory token (re-login on 401);
  snapshots `tenants_total`, `tenants:status`, `subscriptions:status`, `founder_seats_*`,
  `email_*`, `platform_events_unresolved`; caches `bookvas_tenant_cache` and the last 100
  `bookvas_platform_event`; exposes extend-grace / comp-period / toggle-founder for actions.
- `UptimeConnector` — GET site_url + api_health_url per active project, 5s timeout, redirects
  followed → `uptime_check`. Own job (`uptime`, every 5 min).
- `NetlifyConnector` — last 10 deploys per `netlify_site_id` → `deploy_event`; `triggerBuild`.
- `CloudRunConnector` — Cloud Run Admin v2 with ADC: latest ready revision, image, traffic →
  `deploy_event` + `cloud_run_ready`; `restart(service)` = new revision from the same image by
  bumping a template annotation.
- `GithubPollConnector` — hourly latest commit per registry repo (fallback for the webhook).
- `BillingConnector` — BigQuery billing export, current month by `service.description` net of
  credits → `cost_snapshot(gcp, connector)`. Disabled until `BILLING_EXPORT_TABLE` is set.

### `jobs`
- `JobRunner` — `metrics` (all metrics connectors, then alert evaluation), `uptime` (probe, then
  site-down evaluation), `billing`, `summary`, `ga4-discovery`, `alerts`, `github-poll`. Returns
  the contract's `{job, startedAt, finishedAt, snapshotsWritten, errors}`; unconfigured connectors
  appear as `"<source>: skipped (not configured)"`.
- `Ga4DiscoveryService` — GA4 Admin `accountSummaries.list` under `GA4_ACCOUNT_NAME`; links
  properties to rows by measurement id, inserts unknown ones as `client_site` +
  `auto_discovered`, raises a `notice`. `slugFor()` derives slugs.
- `DailySummaryService` — the 07:00 message ("Yesterday: … Sites: all up. Needs you: N."),
  stored in `daily_summary`, sent on the settings channel. Bookings/deposits read `n/a` until the
  Bookvas endpoints exist.
- `InProcessScheduler` — optional timers (`PULSE_INPROCESS_SCHEDULING=true`); prod uses Cloud
  Scheduler because Cloud Run scales to zero.

### `alerts`
- `AlertEngine` — the six kinds: deposit paid (GA4 `event:purchase` increase), founder seat
  claimed (`founder_seats_used` increment), site down (3 consecutive failures, transition-based),
  email failures (`email_failed_24h` > threshold, refires only when the count grows), tenant grace
  (cached subscription moved to GRACE), prospect overdue (`next_action_date < today`, in-app).
  `alert_state` remembers last values/states; an unacknowledged event with the same dedupe key
  blocks a refire.
- `Notifier` — routes by channel with fallback (whatsapp → settings default → email → in-app) and
  reports what was used; site-down alerts also go to email (spec "WhatsApp + email").
  `EmailNotifier` (Spring Mail / Brevo relay, `MAIL_TO`), `WhatsAppNotifier` (Meta Graph API).

### `ask`
- `AskService` — `POST /api/ask` as SSE: `delta {text}` … `done {model, inputTokens,
  outputTokens}` or terminal `error {message}`. Anthropic Java SDK streaming
  (`messages().createStreaming`), no `thinking`/`temperature` overrides; 503 when
  `ANTHROPIC_API_KEY` is absent. The system prompt makes the model a read-only analyst.
- `AskContextBuilder` — compact text context (scope + range snapshots, open alerts, overdue
  prospects, latest summary, costs), capped ~24k chars.
- `AnthropicClientFactory` — lazy client from the key; model `ANTHROPIC_MODEL` (default
  `claude-opus-5`), 4096 max tokens.

### `repository`
Spring Data JPA, one per entity. Aggregations live in `MetricSnapshotRepository` (JPQL constructor
projections `DayValue`, `KeyValue`). Snapshot and deploy writes bypass JPA via `JdbcTemplate`
upserts (`SnapshotWriter`, `DeployService`) so re-runs are idempotent at the database.

### `entity`
Lombok getters/setters, UUID ids (`gen_random_uuid()`), `Instant` ↔ `TIMESTAMPTZ`, `TEXT` +
CHECK constraints ↔ lowercase Java enums in `Enums` (so JSON values equal the contract's literals),
`jsonb` via `@JdbcTypeCode(SqlTypes.JSON)`, `text[]` for `project.github_repos`.

### `dto`
Java records mirroring the contract's TypeScript interfaces field-for-field (camelCase, ISO-8601
UTC instants, integer cents). Nested records where the contract nests.

### `exception`
`ApiException` (any status + optional `code`, e.g. 403 `NOT_ALLOWED`, 503 `NOT_CONFIGURED`,
502 `UPSTREAM_FAILED`), `ResourceNotFoundException` → 404, `ConflictException` → 409,
`IllegalArgumentException` → 400. `GlobalExceptionHandler` renders every error as
`{status:"error", message, code?}` — controllers throw, never try/catch.

## Known trade-offs / future work

- Rate-limit windows and the Bookvas token are in process memory: correct only at
  `--max-instances=1`.
- Bookings, deposits and revenue are `null`/`n/a` until Bookvas adds the endpoints in
  `specs/BOOKVAS-API-GAPS.md`; the `deposit_paid` alert uses the GA4 `purchase` event meanwhile.
- Hourly sparkline points exist only if a connector writes `period='hour'` rows; today the GA4
  connector writes days, so "today" shows the day total at the current hour.
- The Google, Anthropic, Netlify and Meta clients are exercised only against their SDKs' compile
  surface here; first live runs happen once the secrets exist (README "KAGISO-only items").
