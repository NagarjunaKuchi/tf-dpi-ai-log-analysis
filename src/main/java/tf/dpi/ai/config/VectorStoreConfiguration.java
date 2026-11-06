package tf.dpi.ai.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tf.dpi.ai.embedding.EmbeddingProperties;
import tf.dpi.ai.vectorstore.KnowledgeRetrievalService;
import tf.dpi.ai.vectorstore.LogChunkRetrievalService;

/** Two logical collections on pgvector, both created by Flyway (schema initialisation is disabled). */
@Configuration
class VectorStoreConfiguration {

    @Bean
    PgVectorStore logChunkVectorStore(JdbcTemplate jdbc, EmbeddingModel model, EmbeddingProperties props) {
        return store(jdbc, model, props, "log_chunk_vectors");
    }

    @Bean
    PgVectorStore knowledgeVectorStore(JdbcTemplate jdbc, EmbeddingModel model, EmbeddingProperties props) {
        return store(jdbc, model, props, "knowledge_vectors");
    }

    private static PgVectorStore store(JdbcTemplate jdbc, EmbeddingModel model, EmbeddingProperties props, String table) {
        return PgVectorStore.builder(jdbc, model)
                .vectorTableName(table)
                .dimensions(props.dimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false)
                .build();
    }

    @Bean
    LogChunkRetrievalService logChunkRetrievalService(PgVectorStore logChunkVectorStore, EmbeddingProperties props,
            MeterRegistry meters) {
        return new LogChunkRetrievalService(logChunkVectorStore, props, meters);
    }

    @Bean
    KnowledgeRetrievalService knowledgeRetrievalService(PgVectorStore knowledgeVectorStore, EmbeddingProperties props,
            MeterRegistry meters) {
        return new KnowledgeRetrievalService(knowledgeVectorStore, props, meters);
    }
}
