package tf.dpi.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tf.dpi.ai.agent.FailureSignatures.Definition;

class FailureSignaturesTest {

    private final FailureSignatures sigs = FailureSignatures.builtIn();

    /** One realistic positive per built-in signature, plus text that must not match it. */
    private static final Map<String, String> POSITIVE = Map.of(
            "DB_CONNECTION_REFUSED", "Caused by: java.net.ConnectException: Connection refused to db-host:5432",
            "OUT_OF_MEMORY", "java.lang.OutOfMemoryError: Java heap space",
            "ACTIVEMQ_ISSUE", "javax.jms.JMSException: Could not connect to broker failover:(tcp://mq:61616)",
            "UPSTREAM_CONNECTION_RESET", "upstream connect error or disconnect/reset before headers. reset reason: connection termination",
            "TIMEOUT", "java.net.SocketTimeoutException: Read timed out",
            "DNS_RESOLUTION", "java.net.UnknownHostException: keycloak.idp.svc",
            "DISK_FULL", "java.io.IOException: No space left on device",
            "TLS_FAILURE", "javax.net.ssl.SSLHandshakeException: PKIX path building failed",
            "AUTH_FAILURE", "org.springframework.security.access.AccessDeniedException: Access is denied",
            "NULL_POINTER", "java.lang.NullPointerException: Cannot invoke \"String.length()\"");

    @Test
    void everyBuiltInSignatureMatchesItsExampleAndNotUnrelatedText() {
        assertThat(sigs.names()).containsExactlyInAnyOrderElementsOf(POSITIVE.keySet());
        for (var e : POSITIVE.entrySet()) {
            assertThat(sigs.matches(e.getKey(), e.getValue())).as(e.getKey()).isTrue();
            assertThat(sigs.matches(e.getKey(), "INFO request completed in 12 ms status=200")).as(e.getKey() + " negative").isFalse();
        }
    }

    @Test
    void detectReturnsAllMatchingSignaturesInDefinitionOrder() {
        // a pool timeout message hits two signatures: both are only hints
        assertThat(sigs.detect("HikariPool-1 - Connection is not available, request timed out; java.net.SocketTimeoutException"))
                .containsExactly("DB_CONNECTION_REFUSED", "TIMEOUT");
        assertThat(sigs.detect("NumberFormatException: For input string: \"12a\"")).isEmpty();
    }

    @Test
    void categoriesAreNormalisedBecauseModelOutputIsUntrusted() {
        assertThat(FailureSignatures.normalizeCategory("  invalid number-format ")).isEqualTo("INVALID_NUMBER_FORMAT");
        assertThat(FailureSignatures.normalizeCategory("<script>alert(1)</script>")).isEqualTo("SCRIPT_ALERT_1_SCRIPT");
        assertThat(FailureSignatures.normalizeCategory("")).isEqualTo("OTHER");
        assertThat(FailureSignatures.normalizeCategory(null)).isEqualTo("OTHER");
        assertThat(FailureSignatures.normalizeCategory("!!!")).isEqualTo("OTHER");
        assertThat(FailureSignatures.normalizeCategory("A".repeat(200))).hasSize(48);
    }

    @Test
    void customSignaturesAddToOrReplaceBuiltInsAndCanStandAlone() {
        var added = FailureSignatures.of(true, List.of(new Definition("quota exceeded", "(?i)quota exceeded")));
        assertThat(added.isKnown("QUOTA_EXCEEDED")).isTrue();
        assertThat(added.isKnown("OUT_OF_MEMORY")).isTrue();

        var replaced = FailureSignatures.of(true, List.of(new Definition("OUT_OF_MEMORY", "MyHeapProblem")));
        assertThat(replaced.matches("OUT_OF_MEMORY", "java.lang.OutOfMemoryError")).isFalse();
        assertThat(replaced.matches("OUT_OF_MEMORY", "MyHeapProblem")).isTrue();

        var only = FailureSignatures.of(false, List.of(new Definition("X", "x")));
        assertThat(only.names()).containsExactly("X");
        assertThat(FailureSignatures.of(false, List.of()).names()).isEmpty();
    }

    @Test
    void invalidCustomSignaturesFailAtStartupWithoutEchoingThePattern() {
        assertThatThrownBy(() -> FailureSignatures.of(true, List.of(new Definition("BAD", "([unclosed"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("unclosed");
        assertThatThrownBy(() -> FailureSignatures.of(true, List.of(new Definition("", "x")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FailureSignatures.of(true, List.of(new Definition("OTHER", "x")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FailureSignatures.of(true, List.of(new Definition("OK", "")))).isInstanceOf(IllegalArgumentException.class);
    }
}
