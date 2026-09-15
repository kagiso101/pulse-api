# Bookvas API gaps seen from Pulse

**Written:** 2026-09-15 · verified against `bookr-api` (`/api/platform/**`, role SUPER_ADMIN).

Pulse never writes to Bookvas's database and never improvises around a missing endpoint (hard
rule 3). Where the platform API has no answer, Pulse stores nothing, returns `null` and the UI
shows "needs Bookvas endpoint". These are the gaps and the endpoints proposed to close them.
They are changes to **bookr-api**, not to Pulse.

## What Pulse already reads (works today)

| Pulse need | Bookvas endpoint |
|---|---|
| Login | `POST /api/auth/super/login` |
| Tenant list + status | `GET /api/platform/tenants` |
| Subscriptions (plan, status, founder, grace) | `GET /api/platform/subscriptions` |
| Founder seats used / total | `GET /api/platform/pricing-config` |
| Email health 24h / 7d | `GET /api/platform/operations/email-summary` |
| Platform events | `GET /api/platform/operations/events?from&to&page&size&unresolvedOnly` |
| Extend grace / comp period / toggle founder | `POST /api/platform/subscriptions/{id}/extend-grace`, `.../comp-period`, `POST /api/platform/subscriptions/tenant/{tenantId}/toggle-founder` |

## Gaps

### 1. Bookings today / this week across all tenants
Overview headline `bookingsThisWeek` and the daily summary's "N bookings" read `null` / `n/a`.

Proposed: `GET /api/platform/metrics/bookings?from=YYYY-MM-DD&to=YYYY-MM-DD`
→ `{ count: number, byDay: [{ date: "YYYY-MM-DD", count: number }] }`
(count CONFIRMED + COMPLETED bookings by `booking_date`, all tenants).

### 2. Payments completed this week, sum in cents
Overview headline `depositsCents` and the summary's "R X in deposits" read `null` / `n/a`; the
`deposit_paid` alert therefore relies on the GA4 `purchase` event instead.

Proposed: `GET /api/platform/metrics/payments?from&to`
→ `{ completedCount: number, completedCents: number, byDay: [{ date, count, cents }] }`
(status COMPLETE payments by `paid_at`, client deposits + balances, all tenants).

### 3. Revenue this month vs last (subscriptions + deposits)
`ProjectDashboard.bookvas.revenue` is `{ available: false }`.

Proposed: the same payments endpoint called with month ranges, plus a `kind` filter
(`kind=subscription|booking`) so subscription charges and client payments can be shown apart.

### 4. Funnel events
Only `booking_started` is emitted by `bookr-client` today (`AnalyticsService`).
`slot_selected`, `deposit_initiated` and `purchase` are not sent, so the funnel renders
`stale: true` with a note until Bookvas adds them (ACTIVATE-KEYS-SPEC §3.3). No Pulse change
needed once they flow — the GA4 connector already tracks all four event names.

## Nice-to-have (not blocking)

- `GET /api/platform/tenants` could include `planCode` and `subscriptionStatus` directly, saving
  the join Pulse does in `bookvas_tenant_cache`.
- `GET /api/platform/operations/events` accepts `size=100`; a `since=<instant>` cursor would let
  Pulse poll incrementally rather than re-reading a 7-day window.
