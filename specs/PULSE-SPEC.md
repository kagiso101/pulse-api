# PULSE — ROGUETECHNOLOGIES INSIGHTS DASHBOARD

**Written:** 2026-09-15
**Owner:** Kagiso Hadebe
**Working name:** Pulse (rename freely; repos `pulse-web` and `pulse-api`)
**Purpose:** one phone-first screen that tells Kagiso how every project is doing, alerts him when something needs him, and lets him act from his phone.

This spec covers **both repos in one build**. It is ordered so each build stage produces a usable product, but nothing is parked — every feature listed is in scope.

---

## 0. Read this first

**Plain language. One stage at a time. Report at every checkpoint before starting the next.**

### Hard rules

1. **No secret ever reaches the browser.** GA4 service account, Bookvas super-admin token, GCP billing, Netlify token, WhatsApp/Brevo keys — backend only, from Secret Manager.
2. **Pulse is read-heavy and action-light.** Every action (section 6) requires a confirm step in the UI and is logged in `action_log`.
3. **Pulse never writes to Bookvas's database directly.** All Bookvas data comes through Bookvas's own API using its super-admin endpoints. If an endpoint is missing, that's a Bookvas change, reported not improvised.
4. **Single user.** Google sign-in, allow-listed to one email. No user management, no roles.
5. **Same stack, same patterns as Bookvas.** Angular 21 + SignalStore, Spring Boot 4 + Flyway + Postgres, Cloud Run in `africa-south1`, Netlify for the frontend. Reuse conventions; don't invent new ones.
6. **Flyway from V1 with a real schema.** No `ddl-auto`. Learned the hard way on Bookvas.
7. **If anything doesn't match expectations, stop and report.**

### Design

Bookvas CI, dark variant. Porcelain text on near-black; lacquer green `#0E5A45` for good; coral `#E0553B` for anything needing attention. Bricolage Grotesque headlines, Instrument Sans body, **JetBrains Mono for every number**. Phone-first: 390px is the primary layout, desktop is the same layout with more columns. Red is used only when something is wrong — a page with no red means "go to work".

---

## 1. Architecture

```
┌──────────────────────────────────────┐
│  pulse-web  (Angular 21, Netlify)    │
│  Google sign-in → JWT from pulse-api │
└──────────────┬───────────────────────┘
               │ HTTPS/JSON + JWT
┌──────────────▼───────────────────────┐
│  pulse-api  (Spring Boot 4, Cloud Run)│
│  ├─ Postgres (Cloud SQL, new db      │
│  │   `pulse` on bookr-pg for now)    │
│  ├─ Cloud Scheduler → /internal/jobs │
│  └─ Connectors (server-side only):   │
│      GA4 Data API · Bookvas API ·    │
│      Netlify API · Cloud Run Admin · │
│      Cloud Billing · GitHub API ·    │
│      Brevo (email) · WhatsApp        │
└──────────────────────────────────────┘
```

**Why cache in Postgres:** GA4 Data API has quotas, Bookvas shouldn't be hammered, and the morning summary needs yesterday's numbers even if a source is down. Every connector writes a `metric_snapshot`; the UI reads snapshots, never sources directly.

**Refresh cadence:** metrics every 15 minutes; uptime every 5; billing hourly; GitHub on webhook.

---

## 2. Project registry

Projects are **configuration, not code**. Adding one is a row, not a deploy.

```sql
project (
  id, slug, name, kind ENUM(product, agency, portfolio, client_site),
  ga4_property_id, site_url, api_health_url, netlify_site_id,
  cloud_run_service, github_repos TEXT[], color, sort_order, active
)
```

Seed rows:

| slug | name | kind | GA4 | notes |
|---|---|---|---|---|
| `bookvas` | Bookvas | product | `G-2DM7Q481M5` property | + Bookvas API connector |
| `roguetech` | ROGUETECHNOLOGIES | agency | to be created | rogue-tech.co.za |
| `portfolio` | Portfolio | portfolio | `G-416CJXW1LG` property | kagiso-hadebe.netlify.app |
| `bruja-thembi` | Bruja Thembi | client_site | optional | brujathembi.com |

**Client sites** get a `client_view_token` — a read-only shareable link showing just their card. Feature 13.

### 2.1 GA4 auto-discovery — new properties appear on their own

Pulse does not wait to be told about a new GA4 property. Hourly, the `ga4-discovery` job calls the **GA4 Admin API** (`accountSummaries.list`) with the same service account and walks every property under account `ROGUETECHNOLOGIES`.

For each property not yet in `project`:
1. Insert a row: `slug` from the property display name, `kind = client_site` by default, `ga4_property_id` set, `site_url` from the property's first web data stream `defaultUri`, `active = true`, `auto_discovered = true`.
2. Fire an in-app notice: "New project discovered: <name>. Set its kind and URLs in Settings."
3. The pill appears on the next page load.

Kagiso can then correct `kind`, add `api_health_url`, `netlify_site_id`, `cloud_run_service` in Settings. Nothing else is needed for traffic, uptime (once `site_url` is known) and key events to start flowing.

Prerequisite (KAGISO ONLY): grant the Pulse service account **Viewer** at the **account** level in GA4 Admin → Account access management, not per property — that is what makes new properties visible without further clicks.

The `project` table gains `auto_discovered BOOLEAN` and `discovered_at`.

---

## 3. Data model (Flyway V1)

```
project                 — registry above
metric_snapshot         — project_id, source, metric_key, dimension_key?, period (day|hour), value NUMERIC, captured_at
uptime_check            — project_id, target (site|api), status_code, latency_ms, ok BOOLEAN, checked_at
alert_rule              — project_id?, kind, threshold, channel (whatsapp|email), enabled
alert_event             — rule_id, fired_at, payload JSONB, delivered BOOLEAN
action_log              — actor_email, action, target, payload JSONB, result, at
deploy_event            — project_id, repo, sha, branch, message, deployed_at, source (netlify|cloudrun|github)
prospect                — name, business, phone, area, has_website, status ENUM, next_action, next_action_date, notes, updated_at
cost_snapshot           — provider (gcp|netlify|brevo|payfast), period_month, amount_cents, captured_at
daily_summary           — date, body TEXT, sent_at
```

All money in integer cents. All timestamps UTC. SA time is a presentation concern.

---

## 4. Connectors (backend)

Each connector is one class with one job: `fetch()` → list of `metric_snapshot` rows. Connectors are dumb; aggregation happens in queries.

### 4.1 GA4 Data API
Service account with Viewer on each GA4 property. Per project, per day: `activeUsers`, `sessions`, `screenPageViews`, top 10 `pagePath`, `sessionSource`, event counts for `file_download`, `click`, `scroll`, and for Bookvas the funnel events from `ACTIVATE-KEYS-SPEC.md` section 3.3.

### 4.2 Bookvas API
Super-admin JWT (from Secret Manager, refreshed on 401). Pulls: tenant list with status and plan; bookings today/this week; payments completed this week (sum cents); subscriptions by status; founder seats used/total; email health (24h/7d failures); platform events since last poll.

**Gap check:** if any of these lacks an endpoint on Bookvas, list them. Do not build them here.

### 4.3 Uptime
HTTP GET to `site_url` and `api_health_url` per project every 5 min. Records status and latency. Three consecutive failures = down.

### 4.4 Netlify
List deploys per `netlify_site_id`; latest deploy sha, branch, state, time → `deploy_event`.

### 4.5 Cloud Run Admin
For `cloud_run_service`: latest revision, ready status, image, traffic split. Also exposes **restart** (new revision from same image) for feature 8.

### 4.6 GitHub
Webhook receiver for `push` and `deployment` on each repo → `deploy_event`. Fallback: poll latest commit hourly.

### 4.7 Cloud Billing
Current-month cost for project `bookings-prod-503907` via the Billing API or a BigQuery export. Netlify, Brevo and PayFast costs entered manually via a small settings form until their APIs are worth wiring.

### 4.8 Notifications
Brevo transactional email (already configured for Bookvas — reuse the SMTP relay). WhatsApp via the Meta Cloud API to Kagiso's own number only; if that setup is too heavy initially, email only, WhatsApp as a follow-up commit.

---

## 5. Frontend — screens

### 5.1 Shell
Top: project pills — **All · Bookvas · RogueTech · Portfolio · Bruja Thembi** (from registry). Time range: Today · 7d · 30d. Refresh indicator showing last snapshot age. Red dot on a pill if that project has an open alert.

### 5.2 "All" — the morning view
Six big numbers, one row on desktop, 2×3 on phone:
1. Visitors, all projects (7d)
2. Bookings this week
3. Deposits paid this week (R)
4. Founder seats claimed / 20
5. CV downloads (7d)
6. Sites up / total

Below: one card per project — name, three numbers, a 7-day sparkline, a status dot. Below that: **Needs you** — open alerts and prospects with `next_action_date` ≤ today. Empty state: "Nothing needs you. Go to work."

### 5.3 Project view
Header with status, last deploy (sha, branch, minutes ago), uptime %. Then sections by kind:

**Bookvas:** Funnel (feature 3) · Revenue this month vs last, projected (5) · Founder countdown (6) · Tenant leaderboard (4) · Email health · Recent platform events · Actions (7).
**RogueTech / Portfolio / client:** Visitors and sessions over time · Top pages · Sources · Key events (`file_download`, `click` outbound) · Deploys.

### 5.4 Funnel (Bookvas)
Horizontal bar per step: `booking_started → slot_selected → deposit_initiated → purchase`. Drop-off % between steps, coral where drop-off exceeds a threshold. Requires the GA4 funnel events to exist in Bookvas — until they do, show the last known state and an inline note.

### 5.5 Actions (Bookvas)
Per tenant row: **Extend grace +5d · Comp 30d · Toggle founder**. Global: **Restart bookr-api · Redeploy rt-bookings**. Every action opens a confirm sheet stating exactly what will happen, then calls `pulse-api`, which calls the underlying API and writes `action_log`. Result shown inline. Failures shown, never swallowed.

### 5.6 Prospects
Table on desktop, cards on phone. The 33 Blaauwberg names imported from a CSV Kagiso supplies. Fields per section 3. Status pipeline: `to_contact → contacted → demo_booked → pilot → tenant → declined`. Quick actions: set status, set next action + date, add note. Overdue next actions surface in "Needs you".

### 5.7 Costs
One number: this month's total to run everything. Breakdown by provider. Manual-entry form for providers without a connector.

### 5.8 Deploys
Timeline across all repos: sha, branch, message, environment, time. Doubles as the release log.

### 5.9 Ask (AI)
A single input at the bottom of every view. Backend assembles the relevant snapshots for the active project and time range into a compact context and calls the Anthropic API; answer streams back. Suggested prompts: "How did Bookvas do this week?" · "Which prospects are overdue?" · "What's the cost trend?" Read-only — the assistant never triggers actions.

### 5.10 Settings
Alert rules on/off with thresholds. Notification channel. Manual cost entries. Project registry editor (add a project = fill five fields). Client view token generation.

---

## 6. Alerts and the morning summary

Default rules, seeded and editable:

| Kind | Trigger | Channel |
|---|---|---|
| Deposit paid | any Bookvas `purchase` | WhatsApp |
| Founder seat claimed | seats_used increments | WhatsApp |
| Site down | 3 consecutive uptime failures | WhatsApp + email |
| Email failures | 24h failures > 3 | email |
| Tenant entering grace | subscription status → GRACE | email |
| Prospect overdue | next_action_date < today | in-app only |

**Daily summary at 07:00 SAST:** one message. "Yesterday: N bookings, R X in deposits, N portfolio visits, N CV downloads. Sites: all up. Needs you: N." Also stored in `daily_summary` and shown at the top of "All".

---

## 7. Security

- Google sign-in on the frontend → ID token → `pulse-api` verifies with Google, checks email against a single allow-listed value from config, issues its own short-lived JWT.
- `/internal/jobs/**` accepts only Cloud Scheduler with an OIDC token from a dedicated service account.
- GitHub webhook validated by HMAC secret.
- Client view tokens: random 32 bytes, SHA-256 stored, scoped to one project, revocable, read-only endpoints only.
- CSP on Netlify from day one. CORS on the API restricted to the Pulse origins.
- Rate limiting on auth and on actions.

---

## 8. Infrastructure

- New database `pulse` on the existing `bookr-pg` instance (same reasoning as `bookr_sit`; split later if it earns it).
- Cloud Run service `pulse-api`, own service account `pulse-api-run@…` with exactly: Secret Manager accessor on Pulse secrets, Cloud SQL client, Cloud Run viewer + the minimum to create a revision on `bookr-api`, Billing viewer, GA4 via the service account's own credentials JSON in Secret Manager.
- Cloud Scheduler jobs: `pulse-metrics` (*/15), `pulse-uptime` (*/5), `pulse-billing` (hourly), `pulse-summary` (07:00 Africa/Johannesburg).
- Netlify site `pulse-web`, production from `main`, branch deploy from `development`.
- Secrets: `PULSE_JWT_SECRET`, `PULSE_ALLOWED_EMAIL`, `GA4_SA_JSON`, `BOOKVAS_SUPER_EMAIL`, `BOOKVAS_SUPER_PASSWORD`, `NETLIFY_TOKEN`, `GITHUB_WEBHOOK_SECRET`, `BREVO_SMTP_KEY` (reuse), `WHATSAPP_TOKEN`, `ANTHROPIC_API_KEY`.
- Branch protection on `main` in both repos **from the first commit**. CI on `development` and `main`. Check GitHub billing is unlocked before relying on it.

---

## 9. Build order — build everything, verify in sequence

**Everything in this spec is built in one continuous run.** The stages below are not gates that stop work; they are the order in which pieces are wired together so each connector can be verified against real data before the next is layered on top. Do not pause between stages waiting for permission. Pause only at a **KAGISO ONLY** item or when something doesn't match expectations.

Sequence:

1. **Foundation** — both repos, CI, Netlify, Cloud Run, Flyway V1 (full schema from section 3, including `auto_discovered`), Google sign-in, project registry seeded, GA4 discovery job.
2. **Traffic + uptime** — GA4 Data connector, uptime connector, snapshots, "All" view, project view for portfolio/agency/client kinds.
3. **Bookvas layer** — Bookvas API connector, Bookvas project view, funnel section. Report endpoint gaps in a file, keep building.
4. **Alerts + summary** — alert engine, default rules, Brevo email, WhatsApp connector, 07:00 summary, "Needs you".
5. **Deploys + costs + prospects** — Netlify, Cloud Run, GitHub webhook, billing connector, cost form, prospect CRUD + CSV import.
6. **Actions** — confirm sheets, `action_log`, tenant actions, restart/redeploy.
7. **Client views + Ask** — view tokens, read-only card page, Anthropic-backed Ask with streaming.
8. **Settings** — everything editable that section 5.10 lists.

Verification at each step is a written report, not a stop. The final report covers section 10 in full.

**KAGISO ONLY items that will interrupt the run** — do these up front so nothing blocks:
- Create the GA4 service account key and grant it account-level Viewer (section 2.1)
- Create the Bookvas super-admin credential for Pulse in Secret Manager
- Netlify token, GitHub webhook secret, Anthropic API key into Secret Manager
- Decide WhatsApp now or email-only (section 12.3)
- Supply the prospect CSV

## 10. Done means

- [ ] Both repos live, CI green, `main` protected
- [ ] Sign-in restricted to one email
- [ ] All four projects in the registry, pills render
- [ ] GA4 discovery job runs hourly; a test property created in GA4 appears as a pill within the hour without any code change
- [ ] GA4, Bookvas, uptime, Netlify, Cloud Run, GitHub, billing connectors writing snapshots
- [ ] "All" view shows the six numbers with real data
- [ ] Each project view complete for its kind
- [ ] Funnel renders from Bookvas events
- [ ] Alerts fire and deliver; 07:00 summary arrives
- [ ] Prospects imported and editable
- [ ] Actions work with confirm + log
- [ ] Client view token works
- [ ] Ask answers from live snapshots
- [ ] No secret in any bundle — verified by grepping the built frontend for every secret name

---

## 11. Out of scope

1. Multi-user or team access
2. Any write to Bookvas outside its existing super-admin endpoints
3. Native mobile app — the PWA is the phone app
4. Historical backfill beyond what GA4 returns on first pull
5. Replacing GA4 — Pulse reads it, doesn't replace it

---

## 12. Open decisions for Kagiso

1. **Name.** Pulse is a placeholder.
2. **Domain.** `pulse.rogue-tech.co.za`?
3. **WhatsApp now or later.** Meta Cloud API setup is an hour of dashboards; email-only for stage 3 is fine if you'd rather.
4. **Prospect CSV.** Send the 33 names in whatever format they're in.
5. **Selected Work card on the portfolio, or private.** It'd be a strong one.
