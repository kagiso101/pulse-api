-- V5__ops_visibility_alerts.sql (OPS-VISIBILITY, 2026-09-30)
--
-- Go-live night made five situations invisible: checkouts PayFast never answered, a cluster of
-- them (PayFast or the merchant account broken), real signups refused by the rate limiter,
-- catalogue price changes, and merchant verifications that never completed. bookr-api now
-- records each as a platform event; these rules turn those events into Pulse alerts. All on
-- email by Kagiso's decision (no WhatsApp).

ALTER TABLE alert_rule DROP CONSTRAINT IF EXISTS alert_rule_kind_check;
ALTER TABLE alert_rule ADD CONSTRAINT alert_rule_kind_check CHECK (kind IN (
    'deposit_paid', 'founder_seat_claimed', 'site_down', 'email_failures', 'tenant_grace', 'prospect_overdue',
    'checkout_stalled', 'payment_provider_degraded', 'signup_rate_limited', 'plan_price_changed',
    'merchant_verification_stalled'));

INSERT INTO alert_rule (project_id, kind, label, threshold, channel, enabled)
VALUES
  ((SELECT id FROM project WHERE slug = 'bookvas'), 'checkout_stalled',
   'Checkout stalled (no PayFast confirmation after 15 min)',            NULL, 'email', true),
  ((SELECT id FROM project WHERE slug = 'bookvas'), 'payment_provider_degraded',
   'PayFast degraded (3+ stalled checkouts in an hour)',                  NULL, 'email', true),
  ((SELECT id FROM project WHERE slug = 'bookvas'), 'signup_rate_limited',
   'Signup refused by the rate limiter',                                  NULL, 'email', true),
  ((SELECT id FROM project WHERE slug = 'bookvas'), 'plan_price_changed',
   'Plan catalogue price changed',                                        NULL, 'email', true),
  ((SELECT id FROM project WHERE slug = 'bookvas'), 'merchant_verification_stalled',
   'Merchant verification not completed within 24h',                     NULL, 'email', true);
