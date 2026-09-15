package pulse_api.jobs;

import com.google.analytics.admin.v1beta.AccountSummary;
import com.google.analytics.admin.v1beta.AnalyticsAdminServiceClient;
import com.google.analytics.admin.v1beta.AnalyticsAdminServiceSettings;
import com.google.analytics.admin.v1beta.DataStream;
import com.google.analytics.admin.v1beta.ListAccountSummariesRequest;
import com.google.analytics.admin.v1beta.PropertySummary;
import com.google.api.gax.core.FixedCredentialsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.connector.FetchResult;
import pulse_api.connector.GoogleCredentialsFactory;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;
import pulse_api.repository.ProjectRepository;
import pulse_api.service.NoticeService;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * GA4 auto-discovery (spec §2.1): walk every property under the configured account; link
 * properties to registry rows by their web stream's measurement id; insert unknown properties as
 * {@code client_site} rows and raise an in-app notice.
 */
@Slf4j
@Service
public class Ga4DiscoveryService {

    public static final String SOURCE = "ga4-discovery";

    private final GoogleCredentialsFactory credentials;
    private final ProjectRepository projects;
    private final NoticeService notices;
    private final String accountName;

    public Ga4DiscoveryService(GoogleCredentialsFactory credentials, ProjectRepository projects, NoticeService notices,
                               @Value("${app.ga4.account-name:}") String accountName) {
        this.credentials = credentials;
        this.projects = projects;
        this.notices = notices;
        this.accountName = accountName == null ? "" : accountName.trim();
    }

    public boolean configured() {
        return credentials.ga4Configured();
    }

    public record DiscoveredProperty(String propertyId, String displayName, String measurementId, String defaultUri) {}

    @Transactional
    public FetchResult run() {
        if (!configured()) {
            return FetchResult.skipped(SOURCE);
        }
        List<DiscoveredProperty> found;
        try {
            found = listProperties();
        } catch (Exception e) {
            log.warn("GA4 discovery failed: {}", e.toString());
            return FetchResult.failed(SOURCE, e);
        }
        int changed = 0;
        List<String> notes = new ArrayList<>();
        for (DiscoveredProperty prop : found) {
            changed += reconcile(prop, notes);
        }
        return FetchResult.of(changed, notes);
    }

    private List<DiscoveredProperty> listProperties() throws Exception {
        var settings = AnalyticsAdminServiceSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials.ga4Credentials()))
                .build();
        List<DiscoveredProperty> out = new ArrayList<>();
        try (AnalyticsAdminServiceClient client = AnalyticsAdminServiceClient.create(settings)) {
            var request = ListAccountSummariesRequest.newBuilder().setPageSize(200).build();
            for (AccountSummary account : client.listAccountSummaries(request).iterateAll()) {
                if (!accountName.isBlank() && !accountName.equalsIgnoreCase(account.getDisplayName())) {
                    continue;
                }
                for (PropertySummary ps : account.getPropertySummariesList()) {
                    String propertyId = ps.getProperty().replace("properties/", "");
                    String measurementId = null;
                    String defaultUri = null;
                    for (DataStream stream : client.listDataStreams(ps.getProperty()).iterateAll()) {
                        if (stream.getType() == DataStream.DataStreamType.WEB_DATA_STREAM) {
                            measurementId = stream.getWebStreamData().getMeasurementId();
                            defaultUri = stream.getWebStreamData().getDefaultUri();
                            break;
                        }
                    }
                    out.add(new DiscoveredProperty(propertyId, ps.getDisplayName(), measurementId, defaultUri));
                }
            }
        }
        return out;
    }

    /** Returns 1 when a registry row was linked or created. */
    int reconcile(DiscoveredProperty prop, List<String> notes) {
        if (projects.findByGa4PropertyId(prop.propertyId()).isPresent()) {
            return 0;
        }
        Optional<Project> byMeasurement = prop.measurementId() == null ? Optional.empty()
                : projects.findByGa4MeasurementIdIgnoreCase(prop.measurementId());
        if (byMeasurement.isPresent()) {
            Project p = byMeasurement.get();
            p.setGa4PropertyId(prop.propertyId());
            if (p.getSiteUrl() == null && prop.defaultUri() != null && !prop.defaultUri().isBlank()) {
                p.setSiteUrl(prop.defaultUri());
            }
            projects.save(p);
            notes.add(SOURCE + ": linked property " + prop.propertyId() + " to " + p.getSlug());
            return 1;
        }
        Project p = new Project();
        p.setSlug(uniqueSlug(slugFor(prop.displayName()), prop.propertyId()));
        p.setName(prop.displayName() == null || prop.displayName().isBlank() ? "GA4 property " + prop.propertyId() : prop.displayName());
        p.setKind(Enums.ProjectKind.client_site);
        p.setGa4PropertyId(prop.propertyId());
        p.setGa4MeasurementId(prop.measurementId());
        p.setSiteUrl(prop.defaultUri() == null || prop.defaultUri().isBlank() ? null : prop.defaultUri());
        p.setActive(true);
        p.setAutoDiscovered(true);
        p.setDiscoveredAt(Instant.now());
        p.setSortOrder(100);
        projects.save(p);
        notices.create(Enums.NoticeKind.project_discovered,
                "New project discovered: " + p.getName(),
                "New project discovered: " + p.getName() + ". Set its kind and URLs in Settings.",
                "/settings/projects/" + p.getId());
        notes.add(SOURCE + ": discovered property " + prop.propertyId() + " as " + p.getSlug());
        return 1;
    }

    /** "Bruja Thembi — Website (GA4)" → "bruja-thembi-website-ga4". Empty input → "property". */
    public static String slugFor(String displayName) {
        if (displayName == null) {
            return "property";
        }
        String ascii = Normalizer.normalize(displayName, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "property" : slug;
    }

    private String uniqueSlug(String base, String propertyId) {
        if (!projects.existsBySlug(base)) {
            return base;
        }
        String withId = base + "-" + propertyId;
        if (!projects.existsBySlug(withId)) {
            return withId;
        }
        for (int i = 2; i < 1000; i++) {
            if (!projects.existsBySlug(withId + "-" + i)) {
                return withId + "-" + i;
            }
        }
        return withId + "-" + System.currentTimeMillis();
    }
}
