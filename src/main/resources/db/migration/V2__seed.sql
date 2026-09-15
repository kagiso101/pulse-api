-- V2__seed.sql
--
-- The four registry rows from PULSE-SPEC.md §2, the six default alert rules from §6 and the
-- single settings row. GA4 *property* ids are unknown at seed time (the spec only knows the
-- public G- measurement ids); the hourly ga4-discovery job fills ga4_property_id by matching
-- each property's web data stream measurementId against ga4_measurement_id.

INSERT INTO project (slug, name, kind, ga4_measurement_id, site_url, api_health_url, netlify_site_id, cloud_run_service, github_repos, color, sort_order)
VALUES
  ('bookvas', 'Bookvas', 'product', 'G-2DM7Q481M5',
   'https://rt-bookings.netlify.app',
   'https://bookr-api-898880840502.africa-south1.run.app/actuator/health',
   NULL, 'bookr-api',
   ARRAY['kagiso101/bookr-api', 'kagiso101/bookr-client', 'kagiso101/bookr-admin'],
   '#0E5A45', 1),
  ('roguetech', 'ROGUETECHNOLOGIES', 'agency', NULL,
   'https://rogue-tech.co.za', NULL, NULL, NULL, '{}', '#12735A', 2),
  ('portfolio', 'Portfolio', 'portfolio', 'G-416CJXW1LG',
   'https://kagiso-hadebe.netlify.app', NULL, NULL, NULL,
   ARRAY['kagiso101/kagiso-hadebe-portfolio'], '#C98A2D', 3),
  ('bruja-thembi', 'Bruja Thembi', 'client_site', NULL,
   'https://brujathembi.com', NULL, NULL, NULL, '{}', '#E0553B', 4);

-- Default alert rules (§6). site_down is "WhatsApp + email" in the spec: the notifier always
-- copies site_down to email in addition to the rule's channel.
INSERT INTO alert_rule (project_id, kind, label, threshold, channel, enabled)
VALUES
  ((SELECT id FROM project WHERE slug = 'bookvas'), 'deposit_paid',         'Deposit paid (any Bookvas purchase)',        NULL, 'whatsapp', true),
  ((SELECT id FROM project WHERE slug = 'bookvas'), 'founder_seat_claimed', 'Founder seat claimed',                       NULL, 'whatsapp', true),
  (NULL,                                            'site_down',            'Site down (3 consecutive uptime failures)',  3,    'whatsapp', true),
  ((SELECT id FROM project WHERE slug = 'bookvas'), 'email_failures',       'Email failures in 24h above threshold',      3,    'email',    true),
  ((SELECT id FROM project WHERE slug = 'bookvas'), 'tenant_grace',         'Tenant entering grace',                      NULL, 'email',    true),
  (NULL,                                            'prospect_overdue',     'Prospect next action overdue',               NULL, 'in_app',   true);

INSERT INTO app_setting (id, notification_channel) VALUES (1, 'email');
