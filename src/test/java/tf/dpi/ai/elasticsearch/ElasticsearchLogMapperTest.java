package tf.dpi.ai.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tf.dpi.ai.exception.CorruptLogRecordException;
import tf.dpi.ai.model.LogEvent;
import tf.dpi.ai.model.LogLevel;

class ElasticsearchLogMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ElasticsearchLogMapper mapper =
            new ElasticsearchLogMapper(ElasticsearchTestSupport.props().fields());

    private JsonNode doc(String s) throws Exception {
        return json.readTree(s);
    }

    @Test
    void mapsFlatDocument() throws Exception {
        LogEvent e = mapper.map("id1", "logstash-2026.11.06", doc("""
                {"@timestamp":"2026-11-06T10:43:19Z","service":"db-pool","host":"h1","level":"ERROR",
                 "message":"connection refused","traceId":"t1","errorCode":"DB-001"}"""));
        assertThat(e.id()).isEqualTo("id1");
        assertThat(e.level()).isEqualTo(LogLevel.ERROR);
        assertThat(e.service()).isEqualTo("db-pool");
        assertThat(e.traceId()).isEqualTo("t1");
        assertThat(e.errorCode()).isEqualTo("DB-001");
        assertThat(e.timestamp().toString()).isEqualTo("2026-11-06T10:43:19Z");
    }

    @Test
    void mapsNestedPathsAndLiteralDottedKeys() throws Exception {
        var fields = new ElasticsearchProperties.Fields("@timestamp", "service.name", "host.name", "log.level",
                "message", "trace.id", "error.code", "exceptionType", "error.stack_trace");
        var m = new ElasticsearchLogMapper(fields);
        LogEvent e = m.map("id2", "i", doc("""
                {"@timestamp":"2026-11-06T10:43:19Z","service":{"name":"auth-api"},"host.name":"h2",
                 "log":{"level":"warn"},"message":"retry 1 of 3","error":{"stack_trace":"x"}}"""));
        assertThat(e.service()).isEqualTo("auth-api");
        assertThat(e.host()).isEqualTo("h2");
        assertThat(e.level()).isEqualTo(LogLevel.WARN);
        assertThat(e.stackTrace()).isEqualTo("x");
    }

    @Test
    void acceptsEpochMillisTimestamp() throws Exception {
        LogEvent e = mapper.map("id3", "i", doc("{\"@timestamp\":1793983204000,\"message\":\"m\"}"));
        assertThat(e.timestamp().toEpochMilli()).isEqualTo(1793983204000L);
    }

    @Test
    void derivesExceptionTypeFromStackTrace() throws Exception {
        LogEvent e = mapper.map("id4", "i", doc("""
                {"@timestamp":"2026-11-06T10:43:19Z","stack_trace":"java.lang.OutOfMemoryError: Java heap space\\n\\tat a.B.c(B.java:1)"}"""));
        assertThat(e.exceptionType()).isEqualTo("java.lang.OutOfMemoryError");
    }

    @Test
    void joinsArrayStackTraces() throws Exception {
        LogEvent e = mapper.map("id5", "i", doc("""
                {"@timestamp":"2026-11-06T10:43:19Z","stack_trace":["java.io.IOException: x","\\tat a.B.c(B.java:1)"]}"""));
        assertThat(e.stackTrace()).contains("IOException").contains("\n").contains("B.java:1");
    }

    @Test
    void rejectsMissingOrBadTimestampWithoutLeakingContent() throws Exception {
        assertThatThrownBy(() -> mapper.map("bad1", "i", doc("{\"message\":\"password=secret123\"}")))
                .isInstanceOf(CorruptLogRecordException.class)
                .hasMessageNotContaining("secret123");
        assertThatThrownBy(() -> mapper.map("bad2", "i", doc("{\"@timestamp\":\"yesterday\"}")))
                .isInstanceOf(CorruptLogRecordException.class);
    }

    @Test
    void rejectsMissingIdAndNonObjectSource() throws Exception {
        assertThatThrownBy(() -> mapper.map("", "i", doc("{}"))).isInstanceOf(CorruptLogRecordException.class);
        assertThatThrownBy(() -> mapper.map("x", "i", null)).isInstanceOf(CorruptLogRecordException.class);
    }

    @Test
    void onlyNeededFieldsAreCarried() throws Exception {
        LogEvent e = mapper.map("id6", "i", doc("""
                {"@timestamp":"2026-11-06T10:43:19Z","message":"m","authorization":"Bearer abc","customer":{"uin":"1234567890"}}"""));
        assertThat(e.attributes()).isEmpty();
    }
}
