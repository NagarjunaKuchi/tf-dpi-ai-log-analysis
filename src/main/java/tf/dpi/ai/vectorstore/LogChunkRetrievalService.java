package tf.dpi.ai.vectorstore;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import tf.dpi.ai.embedding.EmbeddingProperties;
import tf.dpi.ai.evidence.Evidence;
import tf.dpi.ai.model.LogLevel;

/** Semantic search over sanitized log chunks using Spring AI's {@link VectorStoreDocumentRetriever}. */
public class LogChunkRetrievalService {

    private final VectorStore store;
    private final EmbeddingProperties embedding;
    private final Timer latency;

    public LogChunkRetrievalService(VectorStore logChunkStore, EmbeddingProperties embedding, MeterRegistry meters) {
        this.store = logChunkStore;
        this.embedding = embedding;
        this.latency = Timer.builder("dpi.retrieval.latency").tag("collection", "log_chunks").register(meters);
    }

    public List<Evidence> search(String queryText, SearchFilters filters, int topK, double similarityThreshold) {
        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(store)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(MetadataFilters.logChunks(embedding, filters))
                .build();
        List<Document> docs = latency.record(() -> retriever.retrieve(new Query(queryText)));
        return docs.stream().map(LogChunkRetrievalService::toEvidence).toList();
    }

    static Evidence toEvidence(Document d) {
        Map<String, Object> m = d.getMetadata();
        String chunkId = String.valueOf(m.getOrDefault("chunkId", d.getId()));
        Object ids = m.get("sourceIds");
        List<String> sourceIds = ids instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        return new Evidence(
                Evidence.idFor(Evidence.SourceSystem.PGVECTOR_LOG_CHUNKS, chunkId),
                Evidence.SourceSystem.PGVECTOR_LOG_CHUNKS,
                Evidence.Nature.CURRENT_OBSERVATION,
                String.valueOf(m.getOrDefault("service", "unknown")),
                m.get("fromEpochMs") instanceof Number n ? Instant.ofEpochMilli(n.longValue()) : null,
                LogLevel.parse(String.valueOf(m.getOrDefault("level", "INFO"))),
                d.getText() == null ? "" : d.getText(),
                chunkId,
                sourceIds,
                d.getScore() == null ? 0.0 : d.getScore(),
                null);
    }
}
