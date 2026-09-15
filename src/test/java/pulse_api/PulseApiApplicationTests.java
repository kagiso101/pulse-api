package pulse_api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import pulse_api.entity.AppSetting;
import pulse_api.repository.AlertRuleRepository;
import pulse_api.repository.AppSettingRepository;
import pulse_api.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context load against the real Postgres (Flyway applies V1 + V2, Hibernate validates the mapping)
 * and a check that the seed landed. Needs DB_PASSWORD, PULSE_JWT_SECRET, PULSE_ALLOWED_EMAIL and
 * GOOGLE_CLIENT_ID in the environment — see README "Test".
 */
@SpringBootTest
class PulseApiApplicationTests {

	@Autowired
	ProjectRepository projects;

	@Autowired
	AlertRuleRepository rules;

	@Autowired
	AppSettingRepository settings;

	@Test
	void contextLoadsAndSeedIsPresent() {
		assertThat(projects.count()).isEqualTo(4);
		assertThat(projects.findBySlug("bookvas")).isPresent();
		assertThat(projects.findBySlug("bookvas").get().getGithubRepos())
				.containsExactly("kagiso101/bookr-api", "kagiso101/bookr-client", "kagiso101/bookr-admin");
		assertThat(projects.findBySlug("portfolio").get().getGa4MeasurementId()).isEqualTo("G-416CJXW1LG");
		assertThat(rules.count()).isEqualTo(6);
		assertThat(rules.findByEnabledTrue()).hasSize(6);
		assertThat(settings.findById(AppSetting.SINGLETON_ID)).isPresent()
				.get().extracting(AppSetting::getNotificationChannel).isEqualTo("email");
	}

}
