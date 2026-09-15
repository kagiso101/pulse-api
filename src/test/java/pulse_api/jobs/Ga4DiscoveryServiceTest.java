package pulse_api.jobs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Ga4DiscoveryServiceTest {

    @Test
    void slugForNormalisesDisplayNames() {
        assertThat(Ga4DiscoveryService.slugFor("Bruja Thembi")).isEqualTo("bruja-thembi");
        assertThat(Ga4DiscoveryService.slugFor("  ROGUETECHNOLOGIES — Website (GA4) ")).isEqualTo("roguetechnologies-website-ga4");
        assertThat(Ga4DiscoveryService.slugFor("Café Été & Co.")).isEqualTo("cafe-ete-co");
        assertThat(Ga4DiscoveryService.slugFor("kagiso-hadebe.netlify.app")).isEqualTo("kagiso-hadebe-netlify-app");
    }

    @Test
    void slugForNeverReturnsEmptyAndCapsLength() {
        assertThat(Ga4DiscoveryService.slugFor(null)).isEqualTo("property");
        assertThat(Ga4DiscoveryService.slugFor("!!!")).isEqualTo("property");
        String slug = Ga4DiscoveryService.slugFor("a".repeat(100));
        assertThat(slug).hasSize(60);
        assertThat(Ga4DiscoveryService.slugFor("x".repeat(59) + " y")).doesNotEndWith("-");
    }
}
