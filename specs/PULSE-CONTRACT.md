# PULSE — API CONTRACT & BUILD CONVENTIONS

**Written:** 2026-09-15. Companion to `PULSE-SPEC.md`. This file is the single source of truth for
the HTTP contract between `pulse-web` (Angular 21) and `pulse-api` (Spring Boot 4). Both repos
carry an identical copy under `specs/`. If the contract must change, change it in both.

Shapes below are written as TypeScript interfaces because they double as the frontend models.
Java records mirror them field-for-field (camelCase JSON, ISO-8601 UTC timestamps, money in
integer cents, never floats for money).

---

## 0. Ground rules that both sides must honour

1. Every `/api/**` route needs `Authorization: Bearer <pulse JWT>` except:
   `POST /api/auth/google`, `GET /api/public/client-view/{token}`, `POST /api/webhooks/github`.
2. `/internal/jobs/**` is Cloud-Scheduler-only (Google OIDC token, see §4). Never called by the UI.
3. Errors are always `{ "status": "error", "message": string, "code"?: string }` with a proper HTTP
   status (400 validation, 401 no/invalid token, 403 wrong email / not allowed, 404, 409, 429, 502
   upstream failure, 503 connector not configured).
4. Every action endpoint (§2.9) requires `{ "confirm": true }` in the body and writes `action_log`
   before returning. A failed upstream call is returned as `result: "failed"` with the message —
   never swallowed, never a 500.
5. The UI reads snapshots only. No endpoint proxies a live third-party call except actions and Ask.
6. Time range parameter everywhere: `range=today|7d|30d` (default `7d`). Server resolves it in
   `Africa/Johannesburg` day boundaries, stores/returns UTC instants.

---

## 1. Auth

### `POST /api/auth/google` (public, rate-limited 10/min/IP)
Request: `{ idToken: string }` — a Google Identity Services ID token.
Server verifies signature + `aud == GOOGLE_CLIENT_ID` + `email_verified` + `email == PULSE_ALLOWED_EMAIL`.
Response 200:
```ts
interface AuthResponse { token: string; expiresIn: number /* seconds */; email: string }
```
403 `{code:"NOT_ALLOWED"}` for any other email. Pulse JWT: HS256, 12h, claims `sub=email`, `role=OWNER`.

### `GET /api/auth/me` → `{ email: string }`

---

## 2. Resources

### 2.1 Projects (registry)
```ts
type ProjectKind = 'product' | 'agency' | 'portfolio' | 'client_site';
type UpState = 'up' | 'down' | 'unknown';

interface Project {
  id: string; slug: string; name: string; kind: ProjectKind;
  ga4PropertyId: string | null;        // numeric GA4 property id as string, e.g. "512345678"
  siteUrl: string | null; apiHealthUrl: string | null;
  netlifySiteId: string | null; cloudRunService: string | null;
  githubRepos: string[];               // "owner/repo"
  color: string | null; sortOrder: number; active: boolean;
  autoDiscovered: boolean; discoveredAt: string | null;
  hasClientViewToken: boolean;
  status: { up: UpState; openAlerts: number; lastSnapshotAt: string | null };
}
interface ProjectUpsert {
  slug: string; name: string; kind: ProjectKind; ga4PropertyId?: string | null;
  siteUrl?: string | null; apiHealthUrl?: string | null; netlifySiteId?: string | null;
  cloudRunService?: string | null; githubRepos?: string[]; color?: string | null;
  sortOrder?: number; active?: boolean;
}
```
- `GET /api/projects` → `Project[]` (active first, by sortOrder). Includes inactive ones with `active:false`.
- `POST /api/projects` body `ProjectUpsert` → `Project` (201)
- `PUT /api/projects/{id}` body `ProjectUpsert` → `Project`
- `DELETE /api/projects/{id}` → 204 (soft delete: `active=false`)
- `POST /api/projects/{id}/client-view-token` → `{ token: string; url: string }` — raw token returned ONCE; server stores SHA-256 only. Replaces any existing token.
- `DELETE /api/projects/{id}/client-view-token` → 204

### 2.2 Overview ("All" view)
`GET /api/overview?range=` →
```ts
interface Overview {
  range: 'today' | '7d' | '30d';
  headline: {
    visitors: number | null;          // GA4 activeUsers summed over range, all projects
    bookingsThisWeek: number | null;  // null = Bookvas endpoint gap (see §5)
    depositsCents: number | null;     // null = gap
    founderSeatsUsed: number | null; founderSeatsTotal: number | null;
    cvDownloads: number | null;       // GA4 file_download events on the portfolio property
    sitesUp: number; sitesTotal: number;
  };
  projects: ProjectCard[];
  needsYou: NeedsYouItem[];
  summary: DailySummary | null;       // latest 07:00 summary
  lastSnapshotAt: string | null;
}
interface ProjectCard {
  projectId: string; slug: string; name: string; kind: ProjectKind; color: string | null;
  numbers: { key: string; label: string; value: number | null; unit: 'count' | 'cents' | 'pct' | 'ms' }[]; // exactly 3
  sparkline: number[];                // daily activeUsers for the range (7 or 30 points; today → 24 hourly)
  status: UpState; openAlerts: number;
}
interface NeedsYouItem {
  type: 'alert' | 'prospect';
  id: string; title: string; detail: string; since: string; projectId: string | null; href: string;
}
interface DailySummary { date: string /* YYYY-MM-DD */; body: string; sentAt: string | null }
```
Empty `needsYou` is the "Nothing needs you. Go to work." state — the client renders that copy.

### 2.3 Project dashboard
`GET /api/projects/{slug}/dashboard?range=` →
```ts
interface ProjectDashboard {
  project: Project;
  header: { status: UpState; uptimePct: number | null; lastDeploy: DeployEvent | null; latencyMs: number | null };
  traffic: {
    series: { date: string; activeUsers: number; sessions: number; pageViews: number }[];
    topPages: { path: string; views: number }[];        // max 10
    sources: { source: string; sessions: number }[];    // max 10
    keyEvents: { event: string; count: number }[];      // file_download, click, scroll, + funnel events
    available: boolean;                                 // false when no GA4 property / no snapshots yet
  };
  deploys: DeployEvent[];                               // latest 10
  bookvas: BookvasSection | null;                       // only for kind = product
}
interface BookvasSection {
  funnel: {
    steps: { event: 'booking_started' | 'slot_selected' | 'deposit_initiated' | 'purchase'; count: number | null }[];
    dropoffs: { from: string; to: string; pct: number | null }[];
    stale: boolean; note: string | null;                // note explains missing events
  };
  revenue: { thisMonthCents: number | null; lastMonthCents: number | null; projectedCents: number | null; available: boolean };
  founder: { used: number | null; total: number | null };
  tenants: TenantRow[];
  emailHealth: { sent24h: number; failed24h: number; sent7d: number; failed7d: number; lastSentAt: string | null; smtpConfigured: boolean } | null;
  events: PlatformEvent[];                              // latest 20 Bookvas platform events
}
interface TenantRow {
  tenantId: string; slug: string; businessName: string; status: string;
  subscriptionId: string | null; planCode: string | null; subscriptionStatus: string | null;
  isFounder: boolean; graceUntil: string | null; createdAt: string;
}
interface PlatformEvent { id: string; happenedAt: string; tenantName: string | null; eventType: string; severity: string; message: string; resolvedAt: string | null }
```

### 2.4 Alerts
```ts
type AlertKind = 'deposit_paid' | 'founder_seat_claimed' | 'site_down' | 'email_failures' | 'tenant_grace' | 'prospect_overdue';
type Channel = 'whatsapp' | 'email' | 'in_app';
interface AlertRule { id: string; projectId: string | null; kind: AlertKind; threshold: number | null; channel: Channel; enabled: boolean; label: string }
interface AlertEvent { id: string; ruleId: string; kind: AlertKind; projectId: string | null; firedAt: string; payload: Record<string, unknown>; delivered: boolean; acknowledgedAt: string | null; title: string; detail: string }
```
- `GET /api/alerts/rules` → `AlertRule[]`
- `PUT /api/alerts/rules/{id}` body `{ enabled: boolean; threshold: number | null; channel: Channel }` → `AlertRule`
- `GET /api/alerts/events?open=true|false&limit=50` → `AlertEvent[]` (open = not acknowledged)
- `POST /api/alerts/events/{id}/ack` → `AlertEvent`

### 2.5 Prospects
```ts
type ProspectStatus = 'to_contact' | 'contacted' | 'demo_booked' | 'pilot' | 'tenant' | 'declined';
interface Prospect {
  id: string; name: string; business: string | null; phone: string | null; area: string | null;
  hasWebsite: boolean | null; status: ProspectStatus; nextAction: string | null;
  nextActionDate: string | null /* YYYY-MM-DD */; notes: string | null; updatedAt: string; overdue: boolean;
}
interface ProspectUpsert { name: string; business?: string | null; phone?: string | null; area?: string | null; hasWebsite?: boolean | null; status?: ProspectStatus; nextAction?: string | null; nextActionDate?: string | null; notes?: string | null }
```
- `GET /api/prospects?status=&overdue=true` → `Prospect[]`
- `POST /api/prospects` → `Prospect` (201) · `PUT /api/prospects/{id}` → `Prospect` · `DELETE /api/prospects/{id}` → 204
- `PATCH /api/prospects/{id}/status` body `{ status }` → `Prospect`
- `PATCH /api/prospects/{id}/next-action` body `{ nextAction: string | null; nextActionDate: string | null }` → `Prospect`
- `POST /api/prospects/{id}/notes` body `{ note: string }` → `Prospect` (appends with a timestamp line)
- `POST /api/prospects/import` multipart `file` (CSV, header row; accepted columns case-insensitive:
  `name, business, phone, area, has_website|website, status, next_action, next_action_date, notes`) → `{ imported: number; skipped: number; errors: string[] }`

### 2.6 Costs
```ts
type Provider = 'gcp' | 'netlify' | 'brevo' | 'payfast' | 'other';
interface CostReport { month: string /* YYYY-MM */; totalCents: number; byProvider: { provider: Provider; amountCents: number; source: 'connector' | 'manual'; capturedAt: string }[]; trend: { month: string; totalCents: number }[] /* last 6 months */ }
```
- `GET /api/costs?month=YYYY-MM` (default current month) → `CostReport`
- `PUT /api/costs/manual` body `{ provider: Provider; month: string; amountCents: number }` → `CostReport`

### 2.7 Deploys
```ts
interface DeployEvent { id: string; projectId: string | null; repo: string | null; sha: string; branch: string | null; message: string | null; environment: string | null; deployedAt: string; source: 'netlify' | 'cloudrun' | 'github' }
```
- `GET /api/deploys?projectId=&limit=50` → `DeployEvent[]` newest first

### 2.8 Summary
- `GET /api/summary/latest` → `DailySummary | null`
- `GET /api/summary?limit=14` → `DailySummary[]`

### 2.9 Actions (POST, `{ confirm: true }` body required, rate-limited 20/min)
```ts
interface ActionResult { result: 'ok' | 'failed'; message: string; actionLogId: string }
interface ActionLog { id: string; actorEmail: string; action: string; target: string; payload: Record<string, unknown>; result: string; at: string }
```
- `POST /api/actions/bookvas/subscriptions/{subscriptionId}/extend-grace` body `{ confirm: true, days?: number /* default 5 */ }`
- `POST /api/actions/bookvas/subscriptions/{subscriptionId}/comp-period` body `{ confirm: true, days?: number /* default 30 */ }`
- `POST /api/actions/bookvas/tenants/{tenantId}/toggle-founder` body `{ confirm: true }`
- `POST /api/actions/cloud-run/{service}/restart` body `{ confirm: true }` — new revision from the current image; `service` must equal a registry `cloudRunService`
- `POST /api/actions/netlify/{siteId}/redeploy` body `{ confirm: true }` — `siteId` must equal a registry `netlifySiteId`
- `GET /api/actions/log?limit=50` → `ActionLog[]`

### 2.10 Ask (read-only AI)
`POST /api/ask` body `{ question: string; projectSlug?: string | null; range?: 'today'|'7d'|'30d' }`
Response: `text/event-stream`. Events, in order:
```
event: delta      data: {"text":"..."}      (many)
event: done       data: {"model":"...","inputTokens":n,"outputTokens":n}
event: error      data: {"message":"..."}   (terminal, instead of done)
```
Server assembles a compact context (latest snapshots for the scope + range, open alerts, overdue
prospects, latest summary) and calls the Anthropic Messages API with streaming. The assistant is
instructed it can only describe, never act. Rate-limited 10/min.

### 2.11 Settings
```ts
interface Settings {
  allowedEmailMasked: string;                     // k***@gmail.com
  notificationChannel: 'whatsapp' | 'email';      // default channel for rules that say whatsapp when WhatsApp is not configured
  whatsappConfigured: boolean; emailConfigured: boolean; ga4Configured: boolean; bookvasConfigured: boolean;
  netlifyConfigured: boolean; cloudRunConfigured: boolean; billingConfigured: boolean; anthropicConfigured: boolean;
  timezone: 'Africa/Johannesburg';
}
```
- `GET /api/settings` → `Settings`
- `PUT /api/settings` body `{ notificationChannel }` → `Settings`

### 2.12 Notices (in-app, e.g. GA4 auto-discovery)
```ts
interface Notice { id: string; kind: 'project_discovered' | 'connector_error' | 'info'; title: string; body: string; createdAt: string; readAt: string | null; href: string | null }
```
- `GET /api/notices?unread=true` → `Notice[]` · `POST /api/notices/{id}/read` → 204

---

## 3. Public

### `GET /api/public/client-view/{token}` (no auth, rate-limited 60/min/IP)
```ts
interface ClientView { name: string; siteUrl: string | null; status: UpState; uptimePct30d: number | null;
  traffic: { series: { date: string; activeUsers: number; sessions: number }[]; topPages: { path: string; views: number }[] } | null; generatedAt: string }
```
Invalid or revoked token → 404 (never 403, to avoid confirming a token exists).

---

## 4. Internal & webhooks

- `POST /internal/jobs/{job}` where job ∈ `metrics | uptime | billing | summary | ga4-discovery | alerts | github-poll`.
  Auth: `Authorization: Bearer <Google OIDC ID token>` with `aud == PULSE_API_BASE_URL` and
  `email == SCHEDULER_SA_EMAIL` (verified against Google's certs). Also accepted in local dev only:
  header `X-Job-Token: <JOBS_TOKEN>` when `JOBS_TOKEN` is set (mirrors bookr-api's `app.jobs.token`).
  Response `{ job, startedAt, finishedAt, snapshotsWritten, errors: string[] }`. The `metrics` job runs every connector and then the alert evaluation.
- `POST /api/webhooks/github` — validates `X-Hub-Signature-256` (HMAC-SHA256 with `GITHUB_WEBHOOK_SECRET`), handles `push` and `deployment_status` events → `deploy_event` rows, matching repo `full_name` to `project.github_repos`.

---

## 5. Upstream: Bookvas super-admin API (verified against bookr-api on 2026-09-15)

Base URL `BOOKVAS_API_BASE_URL` (prod `https://bookr-api-898880840502.africa-south1.run.app`). All under `/api/platform/**` need `Authorization: Bearer <super-admin JWT>` (role SUPER_ADMIN, 24h expiry).

| Need (spec §4.2) | Endpoint | Notes |
|---|---|---|
| Login | `POST /api/auth/super/login` `{email,password}` → `{token, expiresIn, email, role}` | rate-limited 10/min; cache the token, refresh on 401 |
| Tenant list with status | `GET /api/platform/tenants` → `PlatformTenantResponse[]` `{id, slug, businessName, industryType, ownerEmail, status, active, createdAt, grandfathered, subscriptionState, inactiveUnpaid}` | plan comes from subscriptions |
| Subscriptions by status, plan, founder flag, grace | `GET /api/platform/subscriptions` → `AdminSubscriptionRow[]` `{id, tenantId, tenantSlug, businessName, planCode, billingCycle, priceCents, status, isFounder, founderNumber, currentPeriodEnd, graceUntil, createdAt}` | |
| Founder seats used/total | `GET /api/platform/pricing-config` → `{founderSeatsTotal, founderSeatsUsed, founderSeatsRemaining}` | |
| Email health 24h/7d | `GET /api/platform/operations/email-summary` → `{sent24h, failed24h, sent7d, failed7d, lastSentAt, lastSentTemplate, smtpConfigured}` | |
| Platform events since last poll | `GET /api/platform/operations/events?from=YYYY-MM-DD&to=&page=0&size=100&unresolvedOnly=` → `{events: PlatformEventRow[], total, unresolvedErrorCount}`; `PlatformEventRow {id, happenedAt, tenantId, tenantName, eventType, severity, message, bookingId, paymentId, requestId, resolvedAt, resolvedNote}` | |
| Needs-attention strip | `GET /api/platform/operations/attention` → `PlatformEventRow[]` | |
| Recent activity | `GET /api/platform/activity` → `[{timestamp, type: TENANT_CREATED\|BOOKING_CREATED, description}]` | recent only, not a count source |
| Extend grace | `POST /api/platform/subscriptions/{id}/extend-grace` `{days}` | |
| Comp period | `POST /api/platform/subscriptions/{id}/comp-period` `{days}` | |
| Toggle founder | `POST /api/platform/subscriptions/tenant/{tenantId}/toggle-founder` | |

**Gaps — Bookvas has no platform-wide endpoint for these. Pulse returns `null` for them and the UI shows "needs Bookvas endpoint". Do not build them here (hard rule 3):**
1. Bookings today / this week across all tenants → proposed `GET /api/platform/metrics/bookings?from&to` → `{count, byDay:[{date,count}]}`.
2. Payments completed this week, sum in cents → proposed `GET /api/platform/metrics/payments?from&to` → `{completedCount, completedCents, byDay:[...]}`.
3. Revenue this month vs last (Bookvas-side subscription + deposit revenue) → same payments endpoint with month ranges.
4. Funnel events: only `booking_started` is emitted by `bookr-client` today (`AnalyticsService`). `slot_selected`, `deposit_initiated`, `purchase` are not yet sent → funnel shows `stale:true` with a note until Bookvas adds them.

Write these into `specs/BOOKVAS-API-GAPS.md` in `pulse-api` (backend agent).

---

## 6. Configuration (env var names; Secret Manager names match)

| Var | Purpose | Required to boot? |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Postgres (`jdbc:postgresql://localhost:55432/pulse` locally) | yes |
| `PULSE_JWT_SECRET` | HS256 key ≥ 32 bytes, fail fast if missing/short | yes |
| `PULSE_ALLOWED_EMAIL` | the one allowed Google account | yes |
| `GOOGLE_CLIENT_ID` | OAuth client id (public) used to check `aud` | yes |
| `PULSE_API_BASE_URL` | audience for Cloud Scheduler OIDC tokens | prod |
| `SCHEDULER_SA_EMAIL` | Cloud Scheduler's service account | prod |
| `JOBS_TOKEN` | local-dev shared secret for `/internal/jobs` | no |
| `CORS_ALLOWED_ORIGINS` | comma list; default `http://localhost:*` | no |
| `GA4_SA_JSON` | service-account JSON (whole file content) for GA4 Data + Admin APIs | no → GA4 connector disabled |
| `GA4_ACCOUNT_NAME` | GA4 account display name to walk (`ROGUETECHNOLOGIES`) | no |
| `BOOKVAS_API_BASE_URL`, `BOOKVAS_SUPER_EMAIL`, `BOOKVAS_SUPER_PASSWORD` | Bookvas connector | no → disabled |
| `NETLIFY_TOKEN` | Netlify API | no → disabled |
| `GCP_PROJECT_ID` (`bookings-prod-503907`), `GCP_REGION` (`africa-south1`) | Cloud Run Admin | no → disabled |
| `BILLING_EXPORT_TABLE` | BigQuery billing export table `project.dataset.gcp_billing_export_v1_XXXX` | no → billing connector disabled, manual entry only |
| `GITHUB_TOKEN`, `GITHUB_WEBHOOK_SECRET` | GitHub poll + webhook HMAC | no → poll disabled / webhook rejects |
| `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD`, `MAIL_FROM`, `MAIL_TO` | Brevo SMTP relay (same as Bookvas) | no → email channel disabled |
| `WHATSAPP_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_TO` | Meta Cloud API | no → falls back to email |
| `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` (default `claude-opus-5`) | Ask | no → Ask returns 503 |
| `SERVER_PORT` / `PORT` | default **8090** (8081 is bookr-api, 8080 is Apache on the dev box) | no |

Every connector must degrade to "not configured" (Settings shows it, jobs skip it, nothing crashes).

---

## 7. Backend conventions (mirror `C:\Projects\Personal\BE\bookr-api`)

- Maven, `spring-boot-starter-parent` **4.0.5**, `<java.version>21</java.version>`, Lombok. Starters:
  `data-jpa`, `security`, `validation`, `webmvc`, `actuator`, `mail`, `flyway` + `flyway-database-postgresql`,
  `postgresql` (runtime), `com.google.cloud.sql:postgres-socket-factory:1.29.0` (runtime), springdoc
  `2.8.5` (docs OFF unless `API_DOCS_ENABLED=true`), `jjwt 0.11.5`. Tests: the `*-test` starters (webmvc-test, data-jpa-test, security-test).
- Root package `pulse_api` (flat snake_case like `bookr_api`). Sub-packages: `config, controller, dto,
  entity, repository, security, service, exception, connector, jobs, alerts, ask`.
- `application.properties` with `${ENV:default}` placeholders and
  `spring.config.import=optional:file:./application-local.properties`; `application-local.properties.example` at the root; `.gitignore` copied from bookr-api.
- `spring.jpa.hibernate.ddl-auto=validate`. Flyway authoritative: `V1__pulse_schema.sql` (full §3 schema
  incl. `auto_discovered`, `discovered_at`, `client_view_token_hash`, plus `notice` and `setting` tables),
  `V2__seed.sql` (4 project rows, 6 default alert rules, default setting row). Never edit a shipped migration.
- Security like bookr-api: stateless, JWT filter, fixed-window `RateLimitFilter`, `CorsConfig` from
  `app.cors.allowed-origins`, headers CSP `default-src 'none'; frame-ancestors 'none'`, HSTS, no-referrer,
  `forward-headers-strategy=framework`, `UserDetailsService` stub so no generated password is logged.
- `GlobalExceptionHandler` → `{status:"error", message, code?}`.
- Connectors: one class each implementing `Connector { String source(); boolean configured(); List<MetricSnapshot> fetch(FetchWindow w); }`. Dumb fetchers; aggregation lives in repository queries.
- Money in `BIGINT` cents; timestamps `TIMESTAMPTZ`; UUID primary keys (`gen_random_uuid()`).
- `Dockerfile` (maven:3.9-eclipse-temurin-21 → eclipse-temurin:21-jre, non-root), `.dockerignore`, `.gcloudignore`,
  `cloudbuild.yaml` + `deploy/cloud-build/{README.md,trigger-sit.yaml,trigger-prod.yaml}` (service `pulse-api-sit` from `development`, `pulse-api` from `main`), `deploy/gcp/bootstrap.sh` (creates SA, secrets placeholders, `pulse` DB on `bookr-pg`, Cloud Scheduler jobs — documented, NOT run).
- `.github/workflows/ci.yml` (postgres:16 service, temurin 21, `./mvnw -B verify`), `.github/dependabot.yml`.
- `PACKAGES.md` (package guide, same style), `README.md`, `specs/` folder.

## 8. Frontend conventions (mirror `C:\Projects\Personal\FE\bookr-admin` and `bookr-client`)

- Angular 21 via `npx -y @angular/cli@21 new pulse-web --style=scss --routing --ssr=false --skip-git --package-manager=npm`. Standalone components, signals, `@angular/build:application`, vitest unit tests.
- Deps: `@ngrx/signals` ^21 (+ `@ngrx/signals/events`), `@angular-architects/ngrx-toolkit` ^21 (`withDevtools`), `lucide-angular` for icons. No Angular Material, no Bootstrap — hand-rolled components on tokens.
- State: one store per feature in `src/app/store/<name>State/` with `<name>.events.ts` (eventGroup),
  `<name>.reducer.ts` (withReducer/on), `<name>.effects.ts` (withEventHandlers), `<name>.facade.ts` (injectDispatch), `<name>.store.ts` (signalStore providedIn root + withDevtools). Components talk to facades only.
- Layout: `src/app/{app.ts,app.html,app.scss,app.config.ts,app.routes.ts}`, `src/app/core/{auth,services}`, `src/pages/<page>/<page>.{ts,html,scss}`, `src/shared/{components,services,models}`.
- Auth: Google Identity Services script (`https://accounts.google.com/gsi/client`) → `POST /api/auth/google` → JWT in `localStorage['pulse_token']`; `authInterceptor` adds Bearer; `sessionExpiryInterceptor` clears session on 401/403 with one toast; `authGuard`/`loginGuard`.
- Environments: `environment.ts` (prod), `environment.development.ts`, `environment.local.ts`, `environment.sit.ts` with `apiBaseUrl`, `googleClientId`, `production`. Build configurations `production | development | sit | local`; `inlineCritical: false` (CSP).
- `netlify.toml`: publish `dist/pulse-web/browser`, NODE_VERSION 24, SPA redirect, branch-deploy `npm run build:sit`, strict CSP:
  `default-src 'self'; script-src 'self' https://accounts.google.com/gsi/client; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://accounts.google.com/gsi/style; font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self' <api urls> https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'` + X-Frame-Options DENY, no-referrer, HSTS, nosniff, `X-Robots-Tag: noindex, nofollow`.
- Design: Bookvas CI **dark variant** tokens in `src/styles/_tokens.scss` (`--pl-*`): bg `#0F1211`, surface `#161A18`, surface-2 `#1D2220`, border `rgba(247,244,239,0.10)`, text porcelain `#F7F4EF`, text-soft `#B8BDB9`, text-muted `#7E857F`, green `#0E5A45` / bright `#12735A` / tint `rgba(14,90,69,0.18)`, coral `#E0553B`, amber `#C98A2D`; fonts Bricolage Grotesque (headlines), Instrument Sans (body), **JetBrains Mono for every number**; radii 8/12/16. Phone-first at 390px; desktop is the same layout with more columns. Red/coral appears only when something is wrong.
- PWA: `@angular/pwa` (manifest + service worker), `display: standalone`, theme colour `#0F1211`.
- `.github/workflows/ci.yml` (node 24, `npm ci`, `npm run build`), `.editorconfig`, prettier `printWidth 100, singleQuote`.

## 9. Local environment facts (for whoever builds/verifies)

- JDK: only `C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1\jbr` (JDK 25). Export
  `JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA 2026.2.1/jbr"` in Git Bash; Maven compiles with `--release 21`. Use the `./mvnw` wrapper (copy `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, `mvnw.cmd` from bookr-api).
- Postgres for verification: Docker container `pulse-pg` → `jdbc:postgresql://localhost:55432/pulse`, user `pulse`, password `pulse`. bookr-api's own test DB is on 5434 — do not touch it.
- Node 24.20, npm 11.19, Angular CLI 21.2.x via npx, Docker 29 running. `gh` and `gcloud` are not installed; nothing may be pushed or deployed from here.
- Network-touching commands (npm, Maven downloads) need the shell sandbox disabled.
