package tf.dpi.ai.vectorstore;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import tf.dpi.ai.embedding.EmbeddingProperties;
import tf.dpi.ai.evidence.Evidence;

/** Search over runbooks and past incidents (second collection). Results are historical knowledge, never current proof. */
public class KnowledgeRetrievalService {

    private final VectorStore store;
    private final EmbeddingProperties embedding;
    private final Timer latency;

    public KnowledgeRetrievalService(VectorStore knowledgeStore, EmbeddingProperties embedding, MeterRegistry meters) {
        this.store = knowledgeStore;
        this.embedding = embedding;
        this.latency = Timer.builder("dpi.retrieval.latency").tag("collection", "knowledge").register(meters);
    }

    /** @param kind RUNBOOK, INCIDENT, PROCEDURE or null for all */
    public List<Evidence> search(String queryText, String kind, int topK, double similarityThreshold) {
        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(store)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(MetadataFilters.knowledge(embedding, kind))
                .build();
        List<Document> docs = latency.record(() -> retriever.retrieve(new Query(queryText)));
        return docs.stream().map(KnowledgeRetrievalService::toEvidence).toList();
    }

    static Evidence toEvidence(Document d) {
        Map<String, Object> m = d.getMetadata();
        String kind = String.valueOf(m.getOrDefault("kind", "RUNBOOK"));
        Evidence.SourceSystem system = "INCIDENT".equals(kind) ? Evidence.SourceSystem.INCIDENT_KB
                : Evidence.SourceSystem.RUNBOOK_KB;
        String ref = m.get("docKey") + "@v" + m.get("version") + "#" + m.get("chunkIndex");
        return new Evidence(
                Evidence.idFor(system, ref),
                system,
                Evidence.Nature.HISTORICAL_KNOWLEDGE,
                String.valueOf(m.getOrDefault("title", "")),
                null,
                null,
                d.getText() == null ? "" : d.getText(),
                String.valueOf(m.getOrDefault("sourceRef", ref)) + " (" + ref + ")",
                List.of(),
                d.getScore() == null ? 0.0 : d.getScore(),
                String.valueOf(m.getOrDefault("validationStatus", "UNVERIFIED")));
    }
}
