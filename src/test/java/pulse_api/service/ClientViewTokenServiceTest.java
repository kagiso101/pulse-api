package pulse_api.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pulse_api.entity.Project;
import pulse_api.repository.ProjectRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ClientViewTokenServiceTest {

    private final ProjectRepository projects = Mockito.mock(ProjectRepository.class);
    private final ClientViewTokenService tokens = new ClientViewTokenService(projects);

    @Test
    void generatesUrlSafe32ByteTokensAndStableSha256() {
        String token = tokens.generate();
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(tokens.generate()).isNotEqualTo(token);
        assertThat(tokens.hash(token)).hasSize(64).isEqualTo(tokens.hash(token));
        assertThat(tokens.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void lookupRoundTripsThroughTheHash() {
        String token = tokens.generate();
        Project project = new Project();
        project.setSlug("bruja-thembi");
        when(projects.findByClientViewTokenHash(tokens.hash(token))).thenReturn(Optional.of(project));

        assertThat(tokens.lookup(token)).contains(project);
    }

    @Test
    void missReturnsEmptyWithoutHittingTheDatabaseForJunk() {
        when(projects.findByClientViewTokenHash(anyString())).thenReturn(Optional.empty());
        assertThat(tokens.lookup("unknown-token")).isEmpty();
        assertThat(tokens.lookup(null)).isEmpty();
        assertThat(tokens.lookup("  ")).isEmpty();
        assertThat(tokens.lookup("x".repeat(129))).isEmpty();
    }
}
