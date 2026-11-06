package tf.dpi.ai.elasticsearch;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tf.dpi.ai.model.LogLevel;

/** Builds read-only search requests. Pure: no I/O, so it is unit-testable without a cluster. */
public class ElasticsearchQueryBuilder {

    private final ElasticsearchProperties props;

    public ElasticsearchQueryBuilder(ElasticsearchProperties props) {
        this.props = props;
    }

    /** Time-window / filter query used for incremental ingestion and ad-hoc lookups. */
    public Query filterQuery(ReadRequest r) {
        ElasticsearchProperties.Fields f = props.fields();
        BoolQuery.Builder bool = new BoolQuery.Builder();

        if (r.afterTimestamp() != null || r.until() != null) {
            bool.filter(q -> q.range(rq -> rq.date(d -> {
                d.field(f.timestamp());
                if (r.afterTimestamp() != null) {
                    d.gte(r.afterTimestamp().toString());
                }
                if (r.until() != null) {
                    d.lt(r.until().toString());
                }
                return d;
            })));
        }
        if (r.minLevel() != null) {
            List<FieldValue> levels = levelSpellings(r.minLevel());
            bool.filter(q -> q.terms(t -> t.field(f.level()).terms(tv -> tv.value(levels))));
        }
        if (!r.services().isEmpty()) {
            bool.filter(q -> q.terms(t -> t.field(f.service()).terms(tv -> tv.value(values(r.services())))));
        }
        if (!r.hosts().isEmpty()) {
            bool.filter(q -> q.terms(t -> t.field(f.host()).terms(tv -> tv.value(values(r.hosts())))));
        }
        if (r.traceId() != null && !r.traceId().isBlank()) {
            bool.filter(q -> q.term(t -> t.field(f.traceId()).value(r.traceId())));
        }
        if (r.errorCode() != null && !r.errorCode().isBlank()) {
            bool.filter(q -> q.term(t -> t.field(f.errorCode()).value(r.errorCode())));
        }
        return Query.of(q -> q.bool(bool.build()));
    }

    /** Full paged search request: ordered by timestamp then tiebreaker, resumed with search_after. */
    public SearchRequest pageRequest(ReadRequest r) {
        Query query = filterQuery(r);
        List<FieldValue> after = searchAfterValues(r.searchAfter());
        return SearchRequest.of(s -> {
            s.index(props.indexPattern())
                    .size(r.size())
                    .trackTotalHits(t -> t.enabled(false))
                    .query(query)
                    .sort(sortOptions());
            if (!after.isEmpty()) {
                s.searchAfter(after);
            }
            return s;
        });
    }

    /** Context lookup: same trace, otherwise same service+host, within the window around the anchor. */
    public SearchRequest contextRequest(String service, String host, String traceId, Instant anchor) {
        ElasticsearchProperties.Fields f = props.fields();
        Instant from = anchor.minus(props.contextBefore());
        Instant to = anchor.plus(props.contextAfter()).plus(Duration.ofMillis(1));
        BoolQuery.Builder bool = new BoolQuery.Builder();
        bool.filter(q -> q.range(rq -> rq.date(d -> d.field(f.timestamp()).gte(from.toString()).lt(to.toString()))));
        if (traceId != null && !traceId.isBlank()) {
            bool.filter(q -> q.term(t -> t.field(f.traceId()).value(traceId)));
        } else {
            bool.filter(q -> q.term(t -> t.field(f.service()).value(service)));
            if (host != null && !host.isBlank()) {
                bool.filter(q -> q.term(t -> t.field(f.host()).value(host)));
            }
        }
        Query query = Query.of(q -> q.bool(bool.build()));
        return SearchRequest.of(s -> s.index(props.indexPattern())
                .size(props.maxContextEvents())
                .trackTotalHits(t -> t.enabled(false))
                .query(query)
                .sort(sortOptions()));
    }

    private List<SortOptions> sortOptions() {
        return List.of(
                SortOptions.of(s -> s.field(f -> f.field(props.fields().timestamp()).order(SortOrder.Asc))),
                SortOptions.of(s -> s.field(f -> f.field(props.tiebreakerField()).order(SortOrder.Asc))));
    }

    /** sort key is [timestampMillis, tiebreaker]. */
    static List<FieldValue> searchAfterValues(List<String> key) {
        if (key.isEmpty()) {
            return List.of();
        }
        if (key.size() != 2) {
            throw new IllegalArgumentException("search_after key must have exactly 2 values");
        }
        return List.of(FieldValue.of(Long.parseLong(key.get(0))), FieldValue.of(key.get(1)));
    }

    private static List<FieldValue> values(Set<String> raw) {
        List<FieldValue> out = new ArrayList<>(raw.size());
        for (String v : raw) {
            out.add(FieldValue.of(v));
        }
        return out;
    }

    /** Level fields are free text in practice, so match the common casings of each level at or above the minimum. */
    static List<FieldValue> levelSpellings(LogLevel min) {
        Set<String> spellings = new LinkedHashSet<>();
        for (LogLevel l : LogLevel.values()) {
            if (l.atLeast(min)) {
                String upper = l.name();
                spellings.add(upper);
                spellings.add(upper.toLowerCase(Locale.ROOT));
                spellings.add(upper.charAt(0) + upper.substring(1).toLowerCase(Locale.ROOT));
                if (l == LogLevel.WARN) {
                    spellings.add("WARNING");
                    spellings.add("warning");
                }
            }
        }
        return values(spellings);
    }
}
