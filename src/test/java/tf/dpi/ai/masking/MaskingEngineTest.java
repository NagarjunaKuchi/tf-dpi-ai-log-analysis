package tf.dpi.ai.masking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaskingEngineTest {

    private final MaskingEngine engine = new MaskingEngine(MaskingPolicy.defaults());

    private void assertMasked(String input, String secret, MaskingCategory category) {
        MaskingResult r = engine.mask(input);
        assertThat(r.text()).doesNotContain(secret);
        assertThat(r.text()).contains("[MASKED:");
        assertThat(r.counts()).containsKey(category);
    }

    @Test
    void masksPasswords() {
        assertMasked("login failed password=S3cr3tPass! for tenant", "S3cr3tPass!", MaskingCategory.PASSWORD);
        assertMasked("{\"password\": \"hunter2hunter2\"}", "hunter2hunter2", MaskingCategory.PASSWORD);
        assertMasked("db_password: abc123xyz", "abc123xyz", MaskingCategory.PASSWORD);
    }

    @Test
    void masksAccessTokens() {
        assertMasked("access_token=abcdef1234567890", "abcdef1234567890", MaskingCategory.ACCESS_TOKEN);
        assertMasked("Bearer abcdefghijklmnop1234", "abcdefghijklmnop1234", MaskingCategory.ACCESS_TOKEN);
    }

    @Test
    void masksJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        MaskingResult r = engine.mask("token validation failed for " + jwt);
        assertThat(r.text()).doesNotContain("eyJhbGci");
        assertThat(r.counts()).containsKey(MaskingCategory.JWT);
    }

    @Test
    void masksApiKeys() {
        assertMasked("x-api-key: 9f8e7d6c5b4a", "9f8e7d6c5b4a", MaskingCategory.API_KEY);
        assertMasked("key AKIAIOSFODNN7EXAMPLE leaked", "AKIAIOSFODNN7EXAMPLE", MaskingCategory.API_KEY);
    }

    @Test
    void masksAuthorizationHeaders() {
        assertMasked("Authorization: Basic dXNlcjpwYXNz", "dXNlcjpwYXNz", MaskingCategory.AUTH_HEADER);
        assertMasked("authorization=Bearer abc.def.ghi", "abc.def.ghi", MaskingCategory.AUTH_HEADER);
    }

    @Test
    void masksPersonalIdentifiers() {
        assertMasked("fullName=Asha Verma, city=Pune", "Asha Verma", MaskingCategory.PERSONAL_IDENTIFIER);
        assertMasked("dob: 1990-04-12", "1990-04-12", MaskingCategory.PERSONAL_IDENTIFIER);
    }

    @Test
    void masksNationalIdAndUin() {
        assertMasked("uin=1234567890 not found", "1234567890", MaskingCategory.NATIONAL_ID);
        assertMasked("lookup for 1234567890123456 failed", "1234567890123456", MaskingCategory.NATIONAL_ID);
        assertMasked("lookup for 4829105736 failed", "4829105736", MaskingCategory.NATIONAL_ID);
    }

    @Test
    void masksPhoneNumbers() {
        assertMasked("sms to +919876543210 failed", "9876543210", MaskingCategory.PHONE);
        assertMasked("call 987-654-3210 back", "987-654-3210", MaskingCategory.PHONE);
    }

    @Test
    void masksEmail() {
        assertMasked("notify asha.verma@example.org now", "asha.verma@example.org", MaskingCategory.EMAIL);
    }

    @Test
    void masksDatabaseCredentials() {
        MaskingResult r = engine.mask("jdbc:postgresql://appuser:Sup3rSecret@db-host:5432/mosip refused");
        assertThat(r.text()).doesNotContain("Sup3rSecret");
        assertThat(r.counts()).containsKey(MaskingCategory.DB_CREDENTIAL);
        assertMasked("FATAL: password authentication failed for user \"mosip_admin\"", "mosip_admin",
                MaskingCategory.DB_CREDENTIAL);
        assertMasked("username=svc_account", "svc_account", MaskingCategory.DB_CREDENTIAL);
    }

    @Test
    void masksActiveMqBrokerUrlCredentials() {
        MaskingResult r = engine.mask("Could not connect to tcp://admin:admin123@broker:61616");
        assertThat(r.text()).doesNotContain("admin123");
    }

    @Test
    void masksPrivateKeys() {
        String key = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA7\nabcdef\n-----END RSA PRIVATE KEY-----";
        MaskingResult r = engine.mask("loaded " + key + " ok");
        assertThat(r.text()).doesNotContain("MIIEpAIBAAKCAQEA7").doesNotContain("BEGIN RSA");
        assertThat(r.counts()).containsKey(MaskingCategory.PRIVATE_KEY);
    }

    @Test
    void masksTruncatedPrivateKeyToEndOfInput() {
        MaskingResult r = engine.mask("key -----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0B");
        assertThat(r.text()).doesNotContain("MIIEvQIBADANBgkqhkiG9w0B");
    }

    @Test
    void masksSecretsInsideStackTracesAndExceptionMessages() {
        String trace = """
                java.sql.SQLException: Access denied for password=Zx9!qLm2 on jdbc:mysql://root:RootPw99@db:3306/x
                \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:155)
                Caused by: java.net.ConnectException: Connection refused (email: ops@example.com)
                """;
        String masked = engine.maskText(trace);
        assertThat(masked).doesNotContain("Zx9!qLm2").doesNotContain("RootPw99").doesNotContain("ops@example.com");
        assertThat(masked).contains("HikariPool.getConnection").contains("Caused by: java.net.ConnectException");
    }

    @Test
    void preservesNonSensitiveDiagnosticContent() {
        String line = "ERROR db-pool connection refused: Connection to db-host:5432 refused, retry 1 of 3";
        assertThat(engine.maskText(line)).isEqualTo(line);
    }

    @Test
    void isIdempotent() {
        String once = engine.maskText("password=abc123 email a@b.com");
        MaskingResult twice = engine.mask(once);
        assertThat(twice.text()).isEqualTo(once);
        assertThat(twice.totalReplacements()).isZero();
    }

    @Test
    void supportsConfiguredCustomRules() {
        MaskingPolicy policy = MaskingPolicy.defaults().withAdditionalRules(List.of(
                MaskingRule.of("internal-ticket", MaskingCategory.CUSTOM, "\\bSEC-\\d{4}\\b", "[MASKED:CUSTOM]")));
        MaskingResult r = new MaskingEngine(policy).mask("see SEC-1234");
        assertThat(r.text()).isEqualTo("see [MASKED:CUSTOM]");
    }

    @Test
    void failsClosedOnOversizedInput() {
        MaskingEngine small = new MaskingEngine(MaskingPolicy.defaults().withLimits(10, Duration.ofSeconds(1)));
        assertThatThrownBy(() -> small.mask("this input is longer than ten characters"))
                .isInstanceOf(MaskingException.class);
    }

    @Test
    void failsClosedWhenResidueRemains() {
        // A policy with no rules but the default residual detectors must refuse content that still holds a secret.
        MaskingPolicy noRules = new MaskingPolicy(List.of(), MaskingPolicy.defaults().residualDetectors(),
                java.util.Set.of(), 1000, Duration.ofSeconds(1));
        assertThatThrownBy(() -> new MaskingEngine(noRules).mask("contact ops@example.com"))
                .isInstanceOf(MaskingException.class)
                .hasMessageNotContaining("ops@example.com");
    }

    @Test
    void failsClosedOnTimeBudget() {
        // 1ns budget with a large input: the deadline is guaranteed to pass during regex evaluation.
        MaskingEngine tiny = new MaskingEngine(MaskingPolicy.defaults().withLimits(1_000_000, Duration.ofNanos(1)));
        String big = "word ".repeat(100_000);
        assertThatThrownBy(() -> tiny.mask(big)).isInstanceOf(MaskingException.class);
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertThat(engine.maskText(null)).isEmpty();
        assertThat(engine.maskText("")).isEmpty();
    }

    @Test
    void structuredJsonMasksSensitiveKeysAndNestedText() {
        var masker = new StructuredLogMasker(engine, new com.fasterxml.jackson.databind.ObjectMapper());
        String json = "{\"user\":{\"email\":\"a@b.org\",\"note\":\"call +919876543210\"},\"token\":\"zzz\",\"items\":[\"password=pw12345\"]}";
        String out = masker.maskAuto(json);
        assertThat(out).doesNotContain("a@b.org").doesNotContain("9876543210").doesNotContain("zzz")
                .doesNotContain("pw12345");
        assertThat(out).contains("[MASKED:");
    }

    @Test
    void structuredMaskerFallsBackToTextForNonJson() {
        var masker = new StructuredLogMasker(engine, new com.fasterxml.jackson.databind.ObjectMapper());
        assertThat(masker.maskAuto("[main] password=abc12345 failed")).doesNotContain("abc12345");
    }

    @Test
    void attributesWithSensitiveKeysAreMasked() {
        var masker = new StructuredLogMasker(engine, new com.fasterxml.jackson.databind.ObjectMapper());
        Map<String, String> out = masker.maskAttributes(Map.of("token", "abc", "region", "eu"));
        assertThat(out.get("token")).startsWith("[MASKED:");
        assertThat(out.get("region")).isEqualTo("eu");
    }
}
