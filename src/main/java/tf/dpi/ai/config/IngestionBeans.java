package tf.dpi.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tf.dpi.ai.chunking.LogChunker;
import tf.dpi.ai.elasticsearch.LogSource;
import tf.dpi.ai.embedding.ChunkEmbedder;
import tf.dpi.ai.embedding.EmbeddingProperties;
import tf.dpi.ai.embedding.SpringAiChunkEmbedder;
import tf.dpi.ai.ingestion.IngestionProperties;
import tf.dpi.ai.ingestion.JdbcIngestionStore;
import tf.dpi.ai.ingestion.LogIngestionService;
import tf.dpi.ai.ingestion.RetryExecutor;
import tf.dpi.ai.masking.EventSanitizer;
import tf.dpi.ai.masking.MaskingEngine;
import tf.dpi.ai.masking.MaskingPolicy;
import tf.dpi.ai.masking.MaskingRule;
import tf.dpi.ai.masking.StructuredLogMasker;
import tf.dpi.ai.observability.IngestionMetrics;

@Configuration
@EnableScheduling
class IngestionBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MaskingEngine maskingEngine(MaskingSettings settings) {
        MaskingPolicy policy = MaskingPolicy.defaults()
                .withAdditionalRules(settings.customRules().stream()
                        .map(r -> MaskingRule.of(r.name(), r.category(), r.pattern(), r.replacement())).toList())
                .withAdditionalSensitiveJsonKeys(java.util.Set.copyOf(settings.additionalSensitiveJsonKeys()))
                .withLimits(settings.maxInputChars(), settings.maxDuration());
        return new MaskingEngine(policy);
    }

    @Bean
    StructuredLogMasker structuredLogMasker(MaskingEngine engine, ObjectMapper mapper) {
        return new StructuredLogMasker(engine, mapper);
    }

    @Bean
    EventSanitizer eventSanitizer(MaskingEngine engine, StructuredLogMasker masker) {
        return new EventSanitizer(engine, masker);
    }

    @Bean
    LogChunker logChunker(ChunkingSettings settings) {
        return new LogChunker(settings.toProperties());
    }

    @Bean
    IngestionMetrics ingestionMetrics(MeterRegistry registry, Clock clock) {
        return new IngestionMetrics(registry, clock);
    }

    @Bean
    JdbcIngestionStore jdbcIngestionStore(JdbcTemplate jdbc, PlatformTransactionManager tx, ObjectMapper json,
            EmbeddingProperties embedding) {
        return new JdbcIngestionStore(jdbc, tx, json, embedding);
    }

    /** Bounded: 2 threads, queue of 8, then AbortPolicy (surfaced as an embedding outage = natural backpressure). */
    @Bean(destroyMethod = "shutdown")
    ExecutorService embeddingExecutor() {
        return new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
                r -> {
                    Thread t = new Thread(r, "embedding-call");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    ChunkEmbedder chunkEmbedder(EmbeddingModel model, EmbeddingProperties props, JdbcIngestionStore store,
            ExecutorService embeddingExecutor, MeterRegistry meters) {
        return new SpringAiChunkEmbedder(model, props, store, embeddingExecutor, meters);
    }

    @Bean
    LogIngestionService logIngestionService(LogSource source, JdbcIngestionStore store, EventSanitizer sanitizer,
            LogChunker chunker, ChunkEmbedder embedder, IngestionProperties props, IngestionMetrics metrics,
            Clock clock) {
        RetryExecutor retry = new RetryExecutor(props.maxBatchAttempts(), props.retryBackoff(),
                attempt -> metrics.retried());
        return new LogIngestionService(source, store, sanitizer, chunker, embedder, store, props, metrics, clock, retry);
    }
}
