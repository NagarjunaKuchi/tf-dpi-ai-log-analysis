package tf.dpi.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.bsc.langgraph4j.CompiledGraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tf.dpi.ai.agent.graph.DpiAgentState;
import tf.dpi.ai.analysis.AnalysisService;
import tf.dpi.ai.masking.MaskingEngine;
import tf.dpi.ai.persistence.AnalysisRepository;

@Configuration
class AnalysisConfiguration {

    @Bean
    AnalysisRepository analysisRepository(JdbcTemplate jdbc, PlatformTransactionManager tx, ObjectMapper json) {
        return new AnalysisRepository(jdbc, tx, json);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService analysisExecutor(@Value("${dpi.analysis.threads:4}") int threads,
            @Value("${dpi.analysis.queue:32}") int queue) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue), r -> {
            Thread t = new Thread(r, "analysis-worker");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    AnalysisService analysisService(AnalysisRepository repo, CompiledGraph<DpiAgentState> graph, MaskingEngine masking,
            ExecutorService analysisExecutor, Clock clock, io.micrometer.core.instrument.MeterRegistry meters) {
        return new AnalysisService(repo, graph, masking, analysisExecutor, clock, meters);
    }
}
