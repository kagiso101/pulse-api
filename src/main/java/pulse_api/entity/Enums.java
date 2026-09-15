package pulse_api.entity;

/**
 * Closed vocabularies stored as TEXT + CHECK constraints in V1. Constant names are deliberately
 * lower_snake_case so they serialise to exactly the contract's JSON values.
 */
public final class Enums {

    private Enums() {}

    public enum ProjectKind { product, agency, portfolio, client_site }

    public enum UpState { up, down, unknown }

    public enum AlertKind { deposit_paid, founder_seat_claimed, site_down, email_failures, tenant_grace, prospect_overdue }

    public enum Channel { whatsapp, email, in_app }

    public enum ProspectStatus { to_contact, contacted, demo_booked, pilot, tenant, declined }

    public enum DeploySource { netlify, cloudrun, github }

    public enum NoticeKind { project_discovered, connector_error, info }

    public enum CostProvider { gcp, netlify, brevo, payfast, other }

    public enum CostSource { connector, manual }

    public enum UptimeTarget { site, api }
}
