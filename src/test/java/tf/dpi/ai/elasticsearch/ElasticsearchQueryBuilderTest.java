package tf.dpi.ai.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tf.dpi.ai.model.LogLevel;

class ElasticsearchQueryBuilderTest {

    private final ElasticsearchQueryBuilder builder = new ElasticsearchQueryBuilder(ElasticsearchTestSupport.props());

    private ReadRequest request(Instant after, List<String> searchAfter, LogLevel min, Set<String> services,
            Set<String> hosts, String trace, String code) {
        return new ReadRequest(after, searchAfter, null, min, services, hosts, trace, code, 100);
    }

    @Test
    void pageRequestUsesConfiguredIndexPatternSizeAndStableSort() {
        SearchRequest r = builder.pageRequest(request(Instant.parse("2026-11-06T00:00:00Z"), List.of(), LogLevel.WARN,
                Set.of(), Set.of(), null, null));
        assertThat(r.index()).containsExactly("logstash-*");
        assertThat(r.size()).isEqualTo(100);
        assertThat(r.sort()).hasSize(2);
        assertThat(r.searchAfter()).isEmpty();
        assertThat(r.toString()).contains("@timestamp").contains("_id");
    }

    @Test
    void timeWindowAndLevelFiltersAreApplied() {
        ReadRequest rr = new ReadRequest(Instant.parse("2026-11-06T00:00:00Z"), List.of(),
                Instant.parse("2026-11-06T01:00:00Z"), LogLevel.WARN, Set.of(), Set.of(), null, null, 10);
        String json = builder.pageRequest(rr).toString();
        assertThat(json).contains("2026-11-06T00:00:00Z").contains("2026-11-06T01:00:00Z");
        assertThat(json).contains("ERROR").contains("WARN").contains("warn").contains("FATAL");
        assertThat(json).doesNotContain("\"INFO\"").doesNotContain("\"DEBUG\"");
    }

    @Test
    void serviceHostTraceAndErrorCodeFiltersAreApplied() {
        SearchRequest r = builder.pageRequest(request(null, List.of(), null, Set.of("auth-api"), Set.of("host-1"),
                "trace-9", "DB-001"));
        assertThat(r.query().bool().filter()).hasSize(4);
        assertThat(r.toString()).contains("auth-api").contains("host-1").contains("trace-9").contains("DB-001");
    }

    @Test
    void searchAfterIsPassedAsLongThenString() {
        SearchRequest r = builder.pageRequest(request(null, List.of("1793983204000", "doc-77"), null, Set.of(),
                Set.of(), null, null));
        assertThat(r.searchAfter()).hasSize(2);
        assertThat(r.searchAfter().get(0).longValue()).isEqualTo(1793983204000L);
        assertThat(r.searchAfter().get(1).stringValue()).isEqualTo("doc-77");
    }

    @Test
    void malformedSearchAfterIsRejected() {
        assertThatThrownBy(() -> builder.pageRequest(request(null, List.of("only-one"), null, Set.of(), Set.of(),
                null, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contextRequestPrefersTraceOverServiceAndHost() {
        SearchRequest byTrace = builder.contextRequest("auth-api", "host-1", "t-1", Instant.parse("2026-11-06T10:00:00Z"));
        assertThat(byTrace.toString()).contains("t-1").doesNotContain("auth-api");
        SearchRequest byService = builder.contextRequest("auth-api", "host-1", "", Instant.parse("2026-11-06T10:00:00Z"));
        assertThat(byService.toString()).contains("auth-api").contains("host-1");
        assertThat(byService.size()).isEqualTo(50);
        assertThat(byService.toString()).contains("2026-11-06T09:59:30Z");
    }

    @Test
    void builderOnlyProducesSearchRequests() {
        // Read-only guarantee at the type level: the builder returns SearchRequest/Query objects exclusively.
        assertThat(SearchRequest.class.isAssignableFrom(
                builder.contextRequest("s", "h", "", Instant.now()).getClass())).isTrue();
    }
}
