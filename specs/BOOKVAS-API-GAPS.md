# Bookvas API gaps seen from Pulse

**Written:** 2026-09-15 · verified against `bookr-api` (`/api/platform/**`, role SUPER_ADMIN).
**Updated:** 2026-09-15 · gaps 1–4 closed on the `development` branches of `bookr-api` and
`bookr-client` (BOOKVAS-PULSE-FIXES-SPEC). Not yet deployed to SIT or production at the time of
writing, and Pulse's `BookvasConnector` does not consume the new endpoints yet — that is a
Pulse-side follow-up (out of scope for the fixes spec).

Pulse never writes to Bookvas's database and never improvises around a missing endpoint (hard
rule 3). Where the platform API has no answer, Pulse stores nothing, returns `null` and the UI
shows "needs Bookvas endpoint".

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

## Gaps — status

All five new endpoints live under `/api/platform/metrics`, are `SUPER_ADMIN`-only (tenant-admin
JWT → 403, isolation-tested), read-only, and documented in bookr-api's OpenAPI under the tag
"Platform metrics". Dates are ISO `YYYY-MM-DD`; money is integer cents; `byDay` series are
zero-filled for every day in the window; windows longer than 366 days are rejected with 400.

### 1. Bookings today / this week across all tenants — CLOSED (bookr-api `development`)
`GET /api/platform/metrics/bookings?from&to` (default window: today, Africa/Johannesburg)
→ `{ from, to, count, total, byStatus: { confirmed, completed, pending, cancelled, … }, byDay: [{ date, count }] }`
`count` = confirmed + completed bookings by `booking_date` (the meaning Pulse asked for);
`total` = every status; `byDay` follows `count`.
Pulse mapping: `bookingsThisWeek` ← `count` over the SA week; summary "N bookings" ← `count` for yesterday.

### 2. Payments completed this week, sum in cents — CLOSED (bookr-api `development`)
`GET /api/platform/metrics/payments?from&to&kind=booking|subscription|all` (default `booking`)
→ `{ from, to, kind, completedCount, completedCents, byDay: [{ date, count, cents }],
     deposits: { count, cents }, fullPayments: { count, cents }, balances: { count, cents },
     bookrFeeCents, practitionerNetCents }`
Only `status = COMPLETE` rows, bucketed by the `completed_at` calendar date; `cents` sums
`client_total_cents`. The R1 merchant-verification charges are excluded. `kind=booking` =
payments without a subscription id.
**Known limitation (accepted 2026-09-15):** `completed_at` is a naive timestamp written in the
server's zone (UTC on Cloud Run), and the day buckets and month totals use that stored date as
is. A payment completed between 00:00 and 02:00 SAST therefore lands on the previous SA day, and
the last two hours of a month fall into the previous month. Pulse's own range resolution is SAST,
so the week and month totals can differ from a strict SA-day reading by those edge payments.
Pulse mapping: `depositsCents` ← `completedCents` (kind `booking`); the `deposit_paid` alert can
now watch `completedCount` increments instead of the GA4 `purchase` event.

### 3. Revenue this month vs last — CLOSED (bookr-api `development`)
`GET /api/platform/metrics/revenue?month=YYYY-MM` (default: current SA month)
→ `{ month, subscriptionCents, transactionalFeeCents, totalCents,
     previousMonth: { month, subscriptionCents, transactionalFeeCents, totalCents } }`
`subscriptionCents` = COMPLETE subscription charges completed in the month; `transactionalFeeCents`
= Bookvas's 3% (`bookr_fee_cents`) on COMPLETE booking payments in the month.
Pulse mapping: `ProjectDashboard.bookvas.revenue` ← `{ thisMonthCents: totalCents,
lastMonthCents: previousMonth.totalCents, projectedCents: totalCents / dayOfMonth × daysInMonth, available: true }`.

### 4. Funnel events — CLOSED (bookr-client `development`)
`bookr-client` now emits, with no PII and only when a measurement id is configured:
`booking_started { tenant_slug }` · `slot_selected { tenant_slug, service_id }` ·
`deposit_initiated { tenant_slug, payment_type }` · `purchase { tenant_slug, transaction_id, value, currency }` ·
`balance_paid { … same … }` · `subscription_created { tenant_slug, plan_code, value, currency }`.
The GA4 connector already tracks the four funnel names; `balance_paid` and `subscription_created`
are new names Pulse may add to its event list. KAGISO ONLY: mark `purchase` and
`subscription_created` as key events in GA4 Admin.

## New, beyond the original list

- `GET /api/platform/metrics/founder-seats` → `{ total, used, remaining }` — plain names for the
  founder countdown (same source as `/pricing-config`).
- `GET /api/platform/metrics/tenants?window=7d|30d` → `[{ tenantId, slug, businessName, planCode,
  subscriptionStatus, bookingsInWindow, lastBookingAt, inGrace }]`, sorted by bookings desc —
  this is the tenant leaderboard in one call and covers the first nice-to-have below.
- `GET /api/public/payments/{mPaymentId}/status` (public poll) now also returns `paymentType` and
  `clientTotalCents`; this is what feeds the GA4 `purchase` value.

## Nice-to-have (not blocking)

- ~~`GET /api/platform/tenants` could include `planCode` and `subscriptionStatus` directly~~ —
  superseded by `/metrics/tenants`.
- `GET /api/platform/operations/events` accepts `size=100`; a `since=<instant>` cursor would let
  Pulse poll incrementally rather than re-reading a 7-day window.

## Pulse follow-up (separate change, not part of the fixes spec)

Point `BookvasConnector` at `/metrics/bookings`, `/metrics/payments`, `/metrics/revenue`,
`/metrics/tenants` and `/metrics/founder-seats`; fill `bookingsThisWeek`, `depositsCents` and the
revenue section from them; switch the `deposit_paid` rule to `completedCount`; drop the funnel
`stale` note once GA4 shows the four events flowing.
