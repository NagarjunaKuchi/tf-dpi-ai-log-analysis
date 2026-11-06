package tf.dpi.ai.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tf.dpi.ai.exception.SourceUnavailableException;

class ElasticsearchLogReaderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ElasticsearchProperties props = ElasticsearchTestSupport.props();
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final List<Long> sleeps = new ArrayList<>();
    private final ElasticsearchLogReader reader = new ElasticsearchLogReader(client,
            new ElasticsearchQueryBuilder(props), new ElasticsearchLogMapper(props.fields()), props,
            new SimpleMeterRegistry(), sleeps::add);

    private ReadRequest anyRequest(int size) {
        return new ReadRequest(null, List.of(), null, null, Set.of(), Set.of(), null, null, size);
    }

    private Hit<ObjectNode> hit(String id, String source, long millis) throws Exception {
        ObjectNode node = (ObjectNode) json.readTree(source);
        return Hit.<ObjectNode>of(h -> h.index("logstash-1").id(id).source(node)
                .sort(List.of(FieldValue.of(millis), FieldValue.of(id))));
    }

    private SearchResponse<ObjectNode> response(List<Hit<ObjectNode>> hits) {
        return SearchResponse.of(r -> r.took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0)).hits(h -> h.hits(hits)));
    }

    @Test
    void returnsEventsRejectedRecordsAndLastSortKey() throws Exception {
        var good = hit("a", "{\"@timestamp\":\"2026-11-06T10:00:00Z\",\"message\":\"m\"}", 1000L);
        var bad = hit("b", "{\"message\":\"no timestamp\"}", 2000L);
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(response(List.of(good, bad)));

        LogPage page = reader.readPage(anyRequest(2));

        assertThat(page.events()).hasSize(1);
        assertThat(page.rejected()).hasSize(1);
        assertThat(page.rejected().get(0).documentId()).isEqualTo("b");
        assertThat(page.lastSortKey()).containsExactly("2000", "b");
        assertThat(page.exhausted()).isFalse();
    }

    @Test
    void shortPageMarksSourceExhausted() throws Exception {
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(response(List.of()));
        LogPage page = reader.readPage(anyRequest(10));
        assertThat(page.isEmpty()).isTrue();
        assertThat(page.exhausted()).isTrue();
        assertThat(page.lastSortKey()).isEmpty();
    }

    @Test
    void retriesTransientFailuresWithBoundedBackoffThenSucceeds() throws Exception {
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class)))
                .thenThrow(new IOException("connection reset"))
                .thenThrow(new IOException("connection reset"))
                .thenReturn(response(List.of()));
        LogPage page = reader.readPage(anyRequest(10));
        assertThat(page.isEmpty()).isTrue();
        verify(client, times(3)).search(any(SearchRequest.class), eq(ObjectNode.class));
        assertThat(sleeps).containsExactly(10L, 20L);
    }

    @Test
    void givesUpAfterMaxRetriesWithoutLeakingDetails() throws Exception {
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenThrow(new IOException("down"));
        assertThatThrownBy(() -> reader.readPage(anyRequest(10))).isInstanceOf(SourceUnavailableException.class);
        verify(client, times(3)).search(any(SearchRequest.class), eq(ObjectNode.class)); // 1 + maxRetries(2)
    }

    @Test
    void nonTransientFailuresAreNotRetried() throws Exception {
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenThrow(new IllegalStateException("bug"));
        assertThatThrownBy(() -> reader.readPage(anyRequest(10))).isInstanceOf(SourceUnavailableException.class);
        verify(client, times(1)).search(any(SearchRequest.class), eq(ObjectNode.class));
    }

    @Test
    void fetchContextSkipsUnmappableDocuments() throws Exception {
        var good = hit("a", "{\"@timestamp\":\"2026-11-06T10:00:00Z\",\"message\":\"m\"}", 1000L);
        var bad = hit("b", "{}", 2000L);
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(response(List.of(good, bad)));
        assertThat(reader.fetchContext("svc", "h", "", Instant.parse("2026-11-06T10:00:00Z"))).hasSize(1);
    }

    @Test
    void clientIsOnlyEverUsedForSearch() throws Exception {
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(response(List.of()));
        reader.readPage(anyRequest(1));
        reader.fetchContext("s", "h", "", Instant.now());
        verify(client, times(2)).search(any(SearchRequest.class), eq(ObjectNode.class));
        org.mockito.Mockito.verifyNoMoreInteractions(client);
    }
}
