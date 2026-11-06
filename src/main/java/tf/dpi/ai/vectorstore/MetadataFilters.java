package tf.dpi.ai.vectorstore;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import tf.dpi.ai.embedding.EmbeddingProperties;
import tf.dpi.ai.model.LogLevel;

/**
 * Builds Spring AI filter expressions. Every expression pins the active embedding model name and version, so vectors
 * produced by different models are never compared in one retrieval space.
 */
public final class MetadataFilters {

    private MetadataFilters() {
    }

    public static Filter.Expression logChunks(EmbeddingProperties embedding, SearchFilters f) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<FilterExpressionBuilder.Op> ops = new ArrayList<>(modelOps(b, embedding));
        if (present(f.service())) {
            ops.add(b.eq("service", f.service()));
        }
        if (f.minLevel() != null) {
            List<Object> levels = new ArrayList<>();
            for (LogLevel l : LogLevel.values()) {
                if (l.atLeast(f.minLevel())) {
                    levels.add(l.name());
                }
            }
            ops.add(b.in("level", levels));
        }
        // overlap semantics: chunk range [fromEpochMs, toEpochMs] intersects the requested window
        if (f.from() != null) {
            ops.add(b.gte("toEpochMs", f.from().toEpochMilli()));
        }
        if (f.to() != null) {
            ops.add(b.lte("fromEpochMs", f.to().toEpochMilli()));
        }
        if (present(f.errorCode())) {
            ops.add(b.eq("errorCode", f.errorCode()));
        }
        if (present(f.traceId())) {
            ops.add(b.eq("traceId", f.traceId()));
        }
        if (present(f.host())) {
            ops.add(b.eq("host", f.host()));
        }
        return fold(b, ops).build();
    }

    /** Only active (latest-version) knowledge documents, optionally restricted to a kind (RUNBOOK / INCIDENT / ...). */
    public static Filter.Expression knowledge(EmbeddingProperties embedding, String kind) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<FilterExpressionBuilder.Op> ops = new ArrayList<>(modelOps(b, embedding));
        ops.add(b.eq("active", true));
        if (present(kind)) {
            ops.add(b.eq("kind", kind));
        }
        return fold(b, ops).build();
    }

    private static List<FilterExpressionBuilder.Op> modelOps(FilterExpressionBuilder b, EmbeddingProperties e) {
        return List.of(b.eq("embeddingModel", e.modelName()), b.eq("embeddingVersion", e.modelVersion()));
    }

    private static FilterExpressionBuilder.Op fold(FilterExpressionBuilder b, List<FilterExpressionBuilder.Op> ops) {
        FilterExpressionBuilder.Op acc = ops.get(0);
        for (int i = 1; i < ops.size(); i++) {
            acc = b.and(acc, ops.get(i));
        }
        return acc;
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
