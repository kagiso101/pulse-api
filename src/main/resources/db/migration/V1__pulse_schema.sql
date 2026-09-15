-- V1__pulse_schema.sql
--
-- Full Pulse schema (PULSE-SPEC.md §3 + §2.1, PULSE-CONTRACT.md §7). Flyway is
-- authoritative from V1: Hibernate only VALIDATES the mapping (ddl-auto=validate), so any
-- drift between entities and this file fails startup instead of being "fixed" silently.
--
-- Conventions: UUID primary keys via gen_random_uuid(), TIMESTAMPTZ everywhere (UTC on the
-- wire; Africa/Johannesburg is a presentation concern), money in BIGINT cents, TEXT with
-- CHECK constraints instead of Postgres enums (cheaper to extend in a later migration).

-- ---------------------------------------------------------------------------------------
-- Project registry (§2). Adding a project is a row, not a deploy.
-- ---------------------------------------------------------------------------------------
CREATE TABLE project (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                            TEXT NOT NULL UNIQUE,
    name                            TEXT NOT NULL,
    kind                            TEXT NOT NULL CHECK (kind IN ('product', 'agency', 'portfolio', 'client_site')),
    ga4_property_id                 TEXT,                      -- numeric GA4 property id, e.g. "512345678"
    ga4_measurement_id              TEXT,                      -- the public "G-..." id; discovery resolves it to a property id
    site_url                        TEXT,
    api_health_url                  TEXT,
    netlify_site_id                 TEXT,
    cloud_run_service               TEXT,
    github_repos                    TEXT[] NOT NULL DEFAULT '{}',
    color                           TEXT,
    sort_order                      INTEGER NOT NULL DEFAULT 0,
    active                          BOOLEAN NOT NULL DEFAULT true,
    auto_discovered                 BOOLEAN NOT NULL DEFAULT false,
    discovered_at                   TIMESTAMPTZ,
    client_view_token_hash          TEXT,                      -- SHA-256 hex of the raw token; raw token is never stored
    client_view_token_created_at    TIMESTAMPTZ,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_project_client_view_token_hash ON project (client_view_token_hash) WHERE client_view_token_hash IS NOT NULL;
CREATE INDEX idx_project_ga4_property ON project (ga4_property_id) WHERE ga4_property_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------
-- Metric snapshots: every connector writes rows here, the UI only ever reads them (§1).
-- One row per (project, source, metric, dimension, period, period_start); re-runs upsert.
-- ---------------------------------------------------------------------------------------
CREATE TABLE metric_snapshot (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    source          TEXT NOT NULL,                             -- ga4 | bookvas | uptime | cloudrun | netlify | github | billing
    metric_key      TEXT NOT NULL,                             -- active_users, sessions, page_views:path, event:<name>, tenants_total ...
    dimension_key   TEXT,                                      -- pagePath / sessionSource / eventName / status
    period          TEXT NOT NULL CHECK (period IN ('day', 'hour')),
    period_start    DATE NOT NULL,                             -- the Africa/Johannesburg day the value belongs to
    period_hour     INTEGER,                                   -- 0..23 for hour rows, NULL for day rows
    metric_value    NUMERIC(20, 4) NOT NULL,
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_metric_snapshot_hour CHECK (
        (period = 'hour' AND period_hour BETWEEN 0 AND 23) OR (period = 'day' AND period_hour IS NULL))
);
CREATE UNIQUE INDEX uq_metric_snapshot_period
    ON metric_snapshot (project_id, source, metric_key, COALESCE(dimension_key, ''), period, period_start, COALESCE(period_hour, -1));
CREATE INDEX idx_metric_snapshot_lookup ON metric_snapshot (project_id, metric_key, captured_at DESC);
CREATE INDEX idx_metric_snapshot_period_start ON metric_snapshot (metric_key, period_start);

-- ---------------------------------------------------------------------------------------
-- Uptime checks (§4.3): one row per probe; "down" = 3 consecutive failures for a target.
-- ---------------------------------------------------------------------------------------
CREATE TABLE uptime_check (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    target          TEXT NOT NULL CHECK (target IN ('site', 'api')),
    status_code     INTEGER,
    latency_ms      INTEGER,
    ok              BOOLEAN NOT NULL,
    error           TEXT,
    checked_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_uptime_check_project_time ON uptime_check (project_id, checked_at DESC);
CREATE INDEX idx_uptime_check_target_time ON uptime_check (project_id, target, checked_at DESC);

-- ---------------------------------------------------------------------------------------
-- Alerts (§6): rules are seeded (V2) and editable; events are what fired.
-- ---------------------------------------------------------------------------------------
CREATE TABLE alert_rule (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID REFERENCES project (id) ON DELETE SET NULL,
    kind            TEXT NOT NULL CHECK (kind IN ('deposit_paid', 'founder_seat_claimed', 'site_down', 'email_failures', 'tenant_grace', 'prospect_overdue')),
    label           TEXT NOT NULL,
    threshold       NUMERIC(20, 4),
    channel         TEXT NOT NULL CHECK (channel IN ('whatsapp', 'email', 'in_app')),
    enabled         BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE alert_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id         UUID NOT NULL REFERENCES alert_rule (id) ON DELETE CASCADE,
    kind            TEXT NOT NULL,
    project_id      UUID REFERENCES project (id) ON DELETE SET NULL,
    fired_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    title           TEXT NOT NULL,
    detail          TEXT NOT NULL DEFAULT '',
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    dedupe_key      TEXT,                                      -- same condition => same key; open events block a refire
    delivered       BOOLEAN NOT NULL DEFAULT false,
    channel         TEXT,                                      -- channel actually used for delivery
    acknowledged_at TIMESTAMPTZ
);
CREATE INDEX idx_alert_event_open ON alert_event (fired_at DESC) WHERE acknowledged_at IS NULL;
CREATE INDEX idx_alert_event_rule ON alert_event (rule_id, fired_at DESC);
CREATE INDEX idx_alert_event_dedupe ON alert_event (dedupe_key) WHERE acknowledged_at IS NULL;

-- Small key/value memory for the alert engine ("what did I see last time?") so increments
-- and transitions (seat claimed, moved to GRACE, went down) fire exactly once.
CREATE TABLE alert_state (
    state_key       TEXT PRIMARY KEY,
    num_value       NUMERIC(20, 4),
    text_value      TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------
-- Actions (§5.5): every action is logged BEFORE the upstream call and updated after.
-- ---------------------------------------------------------------------------------------
CREATE TABLE action_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_email     TEXT NOT NULL,
    action          TEXT NOT NULL,
    target          TEXT NOT NULL,
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    result          TEXT NOT NULL DEFAULT 'pending',           -- pending | ok | failed
    message         TEXT,
    at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);
CREATE INDEX idx_action_log_at ON action_log (at DESC);

-- ---------------------------------------------------------------------------------------
-- Deploys (§4.4-4.6): one timeline across Netlify, Cloud Run and GitHub.
-- ---------------------------------------------------------------------------------------
CREATE TABLE deploy_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID REFERENCES project (id) ON DELETE SET NULL,
    repo            TEXT,
    sha             TEXT NOT NULL,
    branch          TEXT,
    message         TEXT,
    environment     TEXT,
    state           TEXT,                                      -- ready | building | error | success ...
    deployed_at     TIMESTAMPTZ NOT NULL,
    source          TEXT NOT NULL CHECK (source IN ('netlify', 'cloudrun', 'github')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_deploy_event_source_sha_project ON deploy_event (source, sha, COALESCE(project_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_deploy_event_time ON deploy_event (deployed_at DESC);
CREATE INDEX idx_deploy_event_project_time ON deploy_event (project_id, deployed_at DESC);

-- ---------------------------------------------------------------------------------------
-- Prospects (§5.6): the Blaauwberg pipeline.
-- ---------------------------------------------------------------------------------------
CREATE TABLE prospect (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL,
    business            TEXT,
    phone               TEXT,
    area                TEXT,
    has_website         BOOLEAN,
    status              TEXT NOT NULL DEFAULT 'to_contact' CHECK (status IN ('to_contact', 'contacted', 'demo_booked', 'pilot', 'tenant', 'declined')),
    next_action         TEXT,
    next_action_date    DATE,
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_prospect_next_action_date ON prospect (next_action_date) WHERE next_action_date IS NOT NULL;
CREATE INDEX idx_prospect_status ON prospect (status);

-- ---------------------------------------------------------------------------------------
-- Costs (§4.7 / §5.7): connector rows (GCP via BigQuery export) and manual entries.
-- period_month is always the first day of the month.
-- ---------------------------------------------------------------------------------------
CREATE TABLE cost_snapshot (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider        TEXT NOT NULL CHECK (provider IN ('gcp', 'netlify', 'brevo', 'payfast', 'other')),
    period_month    DATE NOT NULL,
    amount_cents    BIGINT NOT NULL,
    currency        TEXT NOT NULL DEFAULT 'ZAR',
    source          TEXT NOT NULL CHECK (source IN ('connector', 'manual')),
    breakdown       JSONB,                                     -- e.g. GCP cost by service.description
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cost_snapshot UNIQUE (provider, period_month, source)
);

-- ---------------------------------------------------------------------------------------
-- Daily 07:00 summary (§6) — one row per SA day.
-- ---------------------------------------------------------------------------------------
CREATE TABLE daily_summary (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    summary_date    DATE NOT NULL UNIQUE,
    body            TEXT NOT NULL,
    channel         TEXT,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------
-- In-app notices (§2.1 step 2, contract §2.12).
-- ---------------------------------------------------------------------------------------
CREATE TABLE notice (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind            TEXT NOT NULL CHECK (kind IN ('project_discovered', 'connector_error', 'info')),
    title           TEXT NOT NULL,
    body            TEXT NOT NULL DEFAULT '',
    href            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at         TIMESTAMPTZ
);
CREATE INDEX idx_notice_unread ON notice (created_at DESC) WHERE read_at IS NULL;

-- ---------------------------------------------------------------------------------------
-- Single settings row (id always 1) — contract §2.11.
-- ---------------------------------------------------------------------------------------
CREATE TABLE app_setting (
    id                      INTEGER PRIMARY KEY CHECK (id = 1),
    notification_channel    TEXT NOT NULL DEFAULT 'email' CHECK (notification_channel IN ('whatsapp', 'email')),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------
-- Bookvas caches (§4.2): raw platform events and the tenant/subscription list, refreshed by
-- the Bookvas connector so the dashboard never calls Bookvas directly (hard rule 3).
-- ---------------------------------------------------------------------------------------
CREATE TABLE bookvas_platform_event (
    id              UUID PRIMARY KEY,                          -- Bookvas's own event id
    happened_at     TIMESTAMPTZ NOT NULL,
    tenant_id       UUID,
    tenant_name     TEXT,
    event_type      TEXT NOT NULL,
    severity        TEXT NOT NULL,
    message         TEXT NOT NULL,
    resolved_at     TIMESTAMPTZ,
    raw             JSONB,
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bookvas_platform_event_time ON bookvas_platform_event (happened_at DESC);

CREATE TABLE bookvas_tenant_cache (
    tenant_id               UUID PRIMARY KEY,                  -- Bookvas tenant id
    slug                    TEXT NOT NULL,
    business_name           TEXT NOT NULL,
    status                  TEXT,
    active                  BOOLEAN,
    subscription_id         UUID,
    plan_code               TEXT,
    subscription_status     TEXT,
    previous_subscription_status TEXT,
    is_founder              BOOLEAN NOT NULL DEFAULT false,
    grace_until             TIMESTAMPTZ,
    tenant_created_at       TIMESTAMPTZ,
    captured_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
