package tf.dpi.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tf.dpi.ai.embedding.EmbeddingProperties;
import tf.dpi.ai.embedding.TextEmbedder;
import tf.dpi.ai.masking.MaskingEngine;
import tf.dpi.ai.runbook.KnowledgeChunker;
import tf.dpi.ai.runbook.KnowledgeIngestionService;

@Configuration
class KnowledgeConfiguration {

    @Bean
    KnowledgeChunker knowledgeChunker(@Value("${dpi.knowledge.max-chunk-chars:1500}") int maxChars) {
        return new KnowledgeChunker(maxChars);
    }

    @Bean
    KnowledgeIngestionService knowledgeIngestionService(JdbcTemplate jdbc, PlatformTransactionManager tx,
            ObjectMapper json, MaskingEngine masking, TextEmbedder embedder, EmbeddingProperties embedding,
            KnowledgeChunker chunker) {
        return new KnowledgeIngestionService(jdbc, tx, json, masking, embedder, embedding, chunker);
    }
}
