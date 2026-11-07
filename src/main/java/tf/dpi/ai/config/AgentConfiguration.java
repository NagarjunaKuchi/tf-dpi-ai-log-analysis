package tf.dpi.ai.config;

import java.time.Clock;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tf.dpi.ai.agent.AgentLlm;
import tf.dpi.ai.agent.AgentProperties;
import tf.dpi.ai.agent.SpringAiAgentLlm;
import tf.dpi.ai.agent.graph.AnalysisGraphFactory;
import tf.dpi.ai.agent.graph.DpiAgentState;
import tf.dpi.ai.agent.nodes.AgentNodes;
import tf.dpi.ai.agent.tools.AgentTools;
import tf.dpi.ai.elasticsearch.LogSource;
import tf.dpi.ai.evidence.EvidenceVerifier;
import tf.dpi.ai.masking.EventSanitizer;
import tf.dpi.ai.masking.MaskingEngine;
import tf.dpi.ai.vectorstore.KnowledgeRetrievalService;
import tf.dpi.ai.vectorstore.LogChunkRetrievalService;

@Configuration
@EnableConfigurationProperties({AgentProperties.class, tf.dpi.ai.agent.SignatureProperties.class})
class AgentConfiguration {

    @Bean
    tf.dpi.ai.agent.FailureSignatures failureSignatures(tf.dpi.ai.agent.SignatureProperties props) {
        return props.toSignatures();
    }

    @Bean
    AgentLlm agentLlm(ChatClient.Builder builder, io.micrometer.core.instrument.MeterRegistry meters,
            tf.dpi.ai.agent.FailureSignatures signatures) {
        return new tf.dpi.ai.observability.MeteredAgentLlm(new SpringAiAgentLlm(builder, signatures.names()), meters);
    }

    @Bean
    AgentTools agentTools(LogChunkRetrievalService logs, KnowledgeRetrievalService knowledge, LogSource source,
            EventSanitizer sanitizer, AgentProperties props) {
        return new AgentTools(logs, knowledge, source, sanitizer, props);
    }

    @Bean
    EvidenceVerifier evidenceVerifier(tf.dpi.ai.agent.FailureSignatures signatures) {
        return new EvidenceVerifier(signatures);
    }

    @Bean
    AgentNodes agentNodes(AgentLlm llm, AgentTools tools, EvidenceVerifier verifier, MaskingEngine masking,
            AgentProperties props, Clock clock) {
        return new AgentNodes(llm, tools, verifier, masking, props, clock);
    }

    /** In-memory checkpoints for now; a durable PostgreSQL-backed saver is listed as pending in the README. */
    @Bean
    CompiledGraph<DpiAgentState> analysisGraph(AgentNodes nodes) {
        return AnalysisGraphFactory.build(nodes, new MemorySaver());
    }
}
