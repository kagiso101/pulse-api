-- V3__bruja_thembi_ga4.sql
--
-- Bruja Thembi now has a GA4 property: account 407905588, property 555346298, web stream
-- "Bruja Thembi — Website" (stream id 15822911391, measurement id G-SWVTT8DJ7S). The site
-- (brujaThembi repo) carries the gtag for this measurement id and brujathembi-dashboard reports
-- on the same property. Setting both ids here means the GA4 connector can pull this project
-- straight away instead of waiting for ga4-discovery to resolve the property id.

UPDATE project
SET ga4_measurement_id = 'G-SWVTT8DJ7S',
    ga4_property_id    = COALESCE(ga4_property_id, '555346298'),
    updated_at         = now()
WHERE slug = 'bruja-thembi';
