package tf.dpi.ai.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tf.dpi.ai.exception.CorruptLogRecordException;
import tf.dpi.ai.model.LogEvent;
import tf.dpi.ai.model.LogLevel;

/** The per-application knobs of the default mapper: timestamp formats, level aliases, exception pattern. */
class ConfigurableMappingTest {

    private static final ElasticsearchProperties.Fields FIELDS = ElasticsearchTestSupport.props().fields();
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode doc(String s) throws Exception {
        return json.readTree(s);
    }

    private static ElasticsearchLogMapper mapper(List<String> formats, String zone, Map<String, String> aliases, String pattern) {
        return new ElasticsearchLogMapper(FIELDS, new ElasticsearchProperties.Mapping(formats, zone, aliases, pattern, ""));
    }

    @Test
    void parsesPatternTimestampInConfiguredZone() throws Exception {
        var m = mapper(List.of("dd/MMM/yyyy:HH:mm:ss"), "Asia/Kolkata", Map.of(), "");
        LogEvent e = m.map("a", "i", doc("{\"@timestamp\":\"06/Nov/2026:16:00:00\"}"));
        assertThat(e.timestamp().toString()).isEqualTo("2026-11-06T10:30:00Z");
    }

    @Test
    void patternWithOffsetUsesTheOffsetNotTheZone() throws Exception {
        var m = mapper(List.of("dd/MMM/yyyy:HH:mm:ss Z"), "UTC", Map.of(), "");
        LogEvent e = m.map("a", "i", doc("{\"@timestamp\":\"06/Nov/2026:16:00:00 +0530\"}"));
        assertThat(e.timestamp().toString()).isEqualTo("2026-11-06T10:30:00Z");
    }

    @Test
    void triesFormatsInOrderAndRejectsWhenNoneMatches() throws Exception {
        var m = mapper(List.of("iso", "yyyy-MM-dd HH:mm:ss,SSS"), "UTC", Map.of(), "");
        assertThat(m.map("a", "i", doc("{\"@timestamp\":\"2026-11-06T10:43:19Z\"}")).timestamp().toString())
                .isEqualTo("2026-11-06T10:43:19Z");
        assertThat(m.map("b", "i", doc("{\"@timestamp\":\"2026-11-06 10:43:19,250\"}")).timestamp().toString())
                .isEqualTo("2026-11-06T10:43:19.250Z");
        assertThatThrownBy(() -> m.map("c", "i", doc("{\"@timestamp\":\"06.11.2026\"}")))
                .isInstanceOf(CorruptLogRecordException.class).hasMessageNotContaining("06.11.2026");
    }

    @Test
    void epochSecondsOnlyReadsNumbersAndNumericStringsAsSeconds() throws Exception {
        var m = mapper(List.of("epoch_seconds"), "UTC", Map.of(), "");
        assertThat(m.map("a", "i", doc("{\"@timestamp\":1793983204}")).timestamp().getEpochSecond()).isEqualTo(1793983204L);
        assertThat(m.map("b", "i", doc("{\"@timestamp\":\"1793983204\"}")).timestamp().getEpochSecond()).isEqualTo(1793983204L);
    }

    @Test
    void levelAliasesMapShortAndNumericSpellings() throws Exception {
        var m = mapper(List.of("iso"), "UTC", Map.of("E", "ERROR", "3", "error", "w", "WARN"), "");
        assertThat(m.map("a", "i", doc("{\"@timestamp\":\"2026-11-06T10:43:19Z\",\"level\":\"E\"}")).level()).isEqualTo(LogLevel.ERROR);
        assertThat(m.map("b", "i", doc("{\"@timestamp\":\"2026-11-06T10:43:19Z\",\"level\":3}")).level()).isEqualTo(LogLevel.ERROR);
        assertThat(m.map("c", "i", doc("{\"@timestamp\":\"2026-11-06T10:43:19Z\",\"level\":\"w\"}")).level()).isEqualTo(LogLevel.WARN);
        // built-in spellings keep working next to the aliases
        assertThat(m.map("d", "i", doc("{\"@timestamp\":\"2026-11-06T10:43:19Z\",\"level\":\"SEVERE\"}")).level()).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void customExceptionPatternCoversOtherLanguages() throws Exception {
        var m = mapper(List.of("iso"), "UTC", Map.of(), "^Traceback.*\\n(?:.*\\n)*?(\\w+(?:\\.\\w+)*Error)\\b");
        LogEvent e = m.map("a", "i", doc("""
                {"@timestamp":"2026-11-06T10:43:19Z","stack_trace":"Traceback (most recent call last):\\n  File x\\nConnectionError: refused"}"""));
        assertThat(e.exceptionType()).isEqualTo("ConnectionError");
        // an explicit exception-type field still wins over the pattern
        LogEvent f = m.map("b", "i", doc("""
                {"@timestamp":"2026-11-06T10:43:19Z","exceptionType":"my.Custom","stack_trace":"Traceback\\nValueError: x"}"""));
        assertThat(f.exceptionType()).isEqualTo("my.Custom");
    }

    @Test
    void invalidConfigurationFailsAtStartupWithoutEchoingValues() {
        assertThatThrownBy(() -> mapper(List.of("iso"), "Not/AZone", Map.of(), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper(List.of("qqqq-invalid[["), "UTC", Map.of(), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper(List.of("iso"), "UTC", Map.of("E", "LOUD"), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper(List.of("iso"), "UTC", Map.of(), "([unclosed")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper(List.of("iso"), "UTC", Map.of(), "no-group")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capturing group");
        assertThatThrownBy(() -> new ElasticsearchProperties.Mapping(List.of(), "UTC", Map.of(), "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
