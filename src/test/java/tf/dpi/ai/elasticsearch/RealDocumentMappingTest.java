package tf.dpi.ai.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tf.dpi.ai.model.LogEvent;
import tf.dpi.ai.model.LogLevel;

/**
 * The DEFAULT configuration against documents taken from the real MOSIP/DPI Logstash index (identifiers replaced by synthetic
 * values). Only the mandatory URI is configured, so this proves the shipped defaults, not a test-only setup.
 */
class RealDocumentMappingTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ElasticsearchProperties props = defaults();
    private final ElasticsearchLogMapper mapper = new ElasticsearchLogMapper(props.fields(), props.mapping());

    private static ElasticsearchProperties defaults() {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t", Map.<String, Object>of("dpi.elasticsearch.uris[0]", "https://es:9200")));
        ConfigurationPropertySources.attach(env);
        return Binder.get(env).bind("dpi.elasticsearch", ElasticsearchProperties.class).get();
    }

    private LogEvent load(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/samples/" + name)) {
            JsonNode hit = json.readTree(in);
            return mapper.map(hit.get("_id").asText(), hit.get("_index").asText(), hit.get("_source"));
        }
    }

    @Test
    void exceptionLoggedInsideTheMessageIsSplitAndClassified() throws Exception {
        LogEvent e = load("mosip-identity-exception.json");
        assertThat(e.id()).isEqualTo("yuA-uKEBDmlILneWtfs6");
        assertThat(e.sourceIndex()).isEqualTo("logstash-2026.11.07");
        assertThat(e.timestamp().toString()).isEqualTo("2026-11-07T23:00:13.838Z");
        assertThat(e.level()).isEqualTo(LogLevel.ERROR);
        assertThat(e.service()).isEqualTo("identity");
        assertThat(e.host()).isEqualTo("identity-57d5dcf9f5-2zhxg");
        assertThat(e.traceId()).isEqualTo("de072ff369622d6c");
        assertThat(e.errorCode()).isEqualTo("IDR-IDC-004");
        assertThat(e.exceptionType()).isEqualTo("io.mosip.idrepository.core.exception.RestServiceException");
        // message keeps the human-readable head, the frames and the Caused-by chain are the stack trace
        assertThat(e.message()).startsWith("service-account-mosip-regproc-client - RestHelper - requestAsync")
                .contains("upstream connect error or disconnect/reset").doesNotContain("\tat io.mosip");
        assertThat(e.stackTrace().stripLeading()).startsWith("at io.mosip.idrepository.core.helper.RestHelper.handleStatusError")
                .contains("Caused by: com.fasterxml.jackson.core.JsonParseException").contains("... 22 more");
        assertThat(e.attributes()).isEmpty();
    }

    @Test
    void plainErrorMessageWithoutExceptionOrTraceId() throws Exception {
        LogEvent e = load("mosip-regproc-error.json");
        assertThat(e.level()).isEqualTo(LogLevel.ERROR);
        assertThat(e.service()).isEqualTo("regproc-notifier");
        assertThat(e.host()).isEqualTo("regproc-notifier-f5d84fd4-zlrqw");
        assertThat(e.traceId()).isEmpty();
        assertThat(e.errorCode()).isEqualTo("IDR-IDC-007");
        assertThat(e.exceptionType()).isEmpty();
        assertThat(e.stackTrace()).isEmpty();
        assertThat(e.message()).contains("Utilities::retrieveUIN()").contains("No Record(s) found");
    }

    @Test
    void errorCodeExtractionCanBeSwitchedOff() throws Exception {
        var m = new ElasticsearchLogMapper(props.fields(), new ElasticsearchProperties.Mapping(
                props.mapping().timestampFormats(), "UTC", Map.of(), "", ""));
        try (InputStream in = getClass().getResourceAsStream("/samples/mosip-regproc-error.json")) {
            JsonNode hit = json.readTree(in);
            assertThat(m.map("x", "i", hit.get("_source")).errorCode()).isEmpty();
        }
    }
}
