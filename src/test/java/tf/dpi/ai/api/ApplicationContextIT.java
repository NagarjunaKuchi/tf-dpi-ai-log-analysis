package tf.dpi.ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tf.dpi.ai.support.TestDatabase;

/**
 * Starts the full application context against a real PostgreSQL (Flyway included). Elasticsearch and Ollama are pointed at
 * closed local ports, so this also proves the agent ends in an explicit FAILED outcome when the LLM is unreachable.
 */
@EnabledIfEnvironmentVariable(named = "DPI_TEST_JDBC_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.flyway.clean-disabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:9/issuer",
        "dpi.security.jwt-issuer-uri=http://127.0.0.1:9/issuer",
        "spring.ai.ollama.base-url=http://127.0.0.1:9",
        "dpi.elasticsearch.uris=http://127.0.0.1:9",
        "dpi.ingestion.enabled=false",
        "dpi.security.rate-limit-per-minute=1000",
        "spring.ai.vectorstore.pgvector.initialize-schema=false"})
class ApplicationContextIT {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        TestDatabase.migrated(); // fresh, migrated database; Flyway in the app then sees it as up to date
        r.add("spring.datasource.url", () -> System.getenv("DPI_TEST_JDBC_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("DPI_TEST_JDBC_USER", "postgres"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("DPI_TEST_JDBC_PASSWORD", ""));
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(String user, String... roles) {
        // jwt() bypasses the app's claim converter (unit-tested separately), so authorities are granted explicitly
        return jwt().jwt(j -> j.subject(user).claim("roles", List.of(roles)))
                .authorities(java.util.Arrays.stream(roles)
                        .map(r -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r))
                        .toArray(org.springframework.security.core.GrantedAuthority[]::new));
    }

    @Test
    void unauthenticatedAndWrongRoleAreRejected() throws Exception {
        mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/analyses").with(as("kb", "KNOWLEDGE_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/analyses/00000000-0000-0000-0000-000000000001/review").with(as("a", "ANALYST"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validationErrorsAreProblemDetailsWithoutEchoingInput() throws Exception {
        var res = mvc.perform(post("/api/v1/analyses").with(as("a", "ANALYST")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"\"}")).andExpect(status().isBadRequest()).andReturn();
        assertThat(res.getResponse().getContentAsString()).contains("Request validation failed");
        assertThat(res.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void analysisWithUnreachableLlmEndsInExplicitFailedReportNotAHang() throws Exception {
        var res = mvc.perform(post("/api/v1/analyses").with(as("alice", "ANALYST")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Why is partner-service failing? email bob@example.com\"}"))
                .andExpect(status().isAccepted()).andReturn();
        JsonNode accepted = json.readTree(res.getResponse().getContentAsString());
        String id = accepted.get("analysisId").asText();

        String status = "QUEUED";
        for (int i = 0; i < 100 && (status.equals("QUEUED") || status.equals("RUNNING")); i++) {
            Thread.sleep(200);
            var s = mvc.perform(get("/api/v1/analyses/" + id).with(as("alice", "ANALYST"))).andExpect(status().isOk())
                    .andReturn();
            status = json.readTree(s.getResponse().getContentAsString()).get("status").asText();
        }
        assertThat(status).isEqualTo("FAILED");

        var rep = mvc.perform(get("/api/v1/analyses/" + id + "/report").with(as("alice", "ANALYST")))
                .andExpect(status().isOk()).andReturn();
        JsonNode report = json.readTree(rep.getResponse().getContentAsString());
        assertThat(report.get("outcome").asText()).isEqualTo("FAILED");
        assertThat(report.get("reviewStatus").asText()).isEqualTo("PENDING_ENGINEER_REVIEW");
        assertThat(jdbc.queryForObject("SELECT query_text FROM analysis_request WHERE analysis_id = ?::uuid", String.class, id))
                .doesNotContain("bob@example.com");

        mvc.perform(post("/api/v1/analyses/" + id + "/review").with(as("rev", "REVIEWER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECTED\",\"comment\":\"llm down\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/analyses/" + id + "/review").with(as("rev2", "REVIEWER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isConflict());
    }
}
