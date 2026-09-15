package pulse_api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The authorization table through the real filter chain (contract §0.1 / §4). */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityMvcTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiRequiresPulseJwt() throws Exception {
        mvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void garbageBearerTokenIsStillUnauthorized() throws Exception {
        mvc.perform(get("/api/projects").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalJobsRequireSchedulerAuth() throws Exception {
        mvc.perform(post("/internal/jobs/metrics"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/jobs/metrics").header("X-Job-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void unknownClientViewTokenIs404NotForbidden() throws Exception {
        mvc.perform(get("/api/public/client-view/nope")).andExpect(status().isNotFound());
    }
}
