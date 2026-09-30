-- V4__bookvas_live_domain.sql
--
-- Bookvas went live on its own domain on 2026-09-30. The registry row seeded in V2 still
-- pointed the uptime check and dashboard links at the Netlify hostname.
UPDATE project
SET site_url = 'https://bookvas.co.za'
WHERE slug = 'bookvas';
