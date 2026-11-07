package tf.dpi.ai.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tf.dpi.ai.model.LogLevel;

/**
 * Queries built from the DEFAULT configuration for the real index layout: dynamically mapped strings are analysed text with a
 * ".keyword" sub-field, and the error code exists only inside the message text.
 */
class RealIndexQueryTest {

    private static ElasticsearchProperties defaults(Map<String, Object> overrides) {
        var all = new java.util.HashMap<String, Object>(overrides);
        all.put("dpi.elasticsearch.uris[0]", "https://es:9200");
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t", all));
        ConfigurationPropertySources.attach(env);
        return Binder.get(env).bind("dpi.elasticsearch", ElasticsearchProperties.class).get();
    }

    private static ReadRequest request(String trace, String code) {
        return new ReadRequest(Instant.parse("2026-11-07T00:00:00Z"), List.of(), null, LogLevel.WARN, Set.of("regproc-notifier"),
                Set.of("regproc-notifier-f5d84fd4-zlrqw"), trace, code, 100);
    }

    @Test
    void exactValueFiltersUseTheKeywordSubFields() {
        String json = new ElasticsearchQueryBuilder(defaults(Map.of())).pageRequest(request("de072ff369622d6c", null)).toString();
        assertThat(json).contains("level.keyword").contains("kubernetes.container_name.keyword")
                .contains("kubernetes.pod_name.keyword").contains("traceId.keyword");
        // range and sort stay on the date field itself
        assertThat(json).contains("\"@timestamp\"").doesNotContain("@timestamp.keyword");
    }

    @Test
    void errorCodeIsSearchedInTheMessageBecauseThereIsNoSuchField() {
        String json = new ElasticsearchQueryBuilder(defaults(Map.of())).pageRequest(request(null, "IDR-IDC-004")).toString();
        assertThat(json).contains("match_phrase").contains("IDR-IDC-004").doesNotContain("errorCode");
    }

    @Test
    void anExplicitErrorCodeFieldIsFilteredAsExactValue() {
        String json = new ElasticsearchQueryBuilder(defaults(Map.of("dpi.elasticsearch.fields.error-code", "error.code")))
                .pageRequest(request(null, "DB-001")).toString();
        assertThat(json).contains("error.code.keyword").doesNotContain("match_phrase");
    }

    @Test
    void emptySuffixFiltersTheFieldsDirectlyForExplicitKeywordMappings() {
        String json = new ElasticsearchQueryBuilder(defaults(Map.of("dpi.elasticsearch.keyword-suffix", ""))).pageRequest(request("t1", null)).toString();
        assertThat(json).doesNotContain(".keyword").contains("\"traceId\"").contains("\"kubernetes.container_name\"");
    }

    @Test
    void contextLookupAlsoUsesKeywordFields() {
        String json = new ElasticsearchQueryBuilder(defaults(Map.of()))
                .contextRequest("regproc-notifier", "regproc-notifier-f5d84fd4-zlrqw", "", Instant.parse("2026-11-07T23:01:08Z")).toString();
        assertThat(json).contains("kubernetes.container_name.keyword").contains("kubernetes.pod_name.keyword");
    }
}
