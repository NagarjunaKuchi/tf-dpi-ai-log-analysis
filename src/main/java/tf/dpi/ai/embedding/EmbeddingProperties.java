package tf.dpi.ai.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding configuration. {@code modelName} and {@code modelVersion} are stored with every vector; retrieval
 * filters on them so vectors from different models are never compared. Changing either requires re-embedding
 * (see EmbeddingMigration notes in docs); {@code dimensions} must equal the pgvector column dimension.
 */
@ConfigurationProperties("dpi.embedding")
public record EmbeddingProperties(
        @DefaultValue("bge-m3") String modelName,
        @DefaultValue("1") String modelVersion,
        @DefaultValue("1024") int dimensions,
        @DefaultValue("32") int batchSize,
        @DefaultValue("30s") Duration timeout) {

    public EmbeddingProperties {
        if (dimensions < 1 || batchSize < 1) {
            throw new IllegalArgumentException("dpi.embedding.dimensions and batch-size must be positive");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("dpi.embedding.timeout must be positive");
        }
    }
}
