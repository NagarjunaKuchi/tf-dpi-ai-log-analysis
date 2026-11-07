package tf.dpi.ai.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tf.dpi.ai.elasticsearch.ElasticsearchHealthIndicator;
import tf.dpi.ai.elasticsearch.ElasticsearchLogMapper;
import tf.dpi.ai.elasticsearch.LogEventMapper;
import tf.dpi.ai.elasticsearch.ElasticsearchLogReader;
import tf.dpi.ai.elasticsearch.ElasticsearchProperties;
import tf.dpi.ai.elasticsearch.ElasticsearchQueryBuilder;
import tf.dpi.ai.elasticsearch.LogSource;

@Configuration
class ElasticsearchBeans {

    @Bean
    ElasticsearchQueryBuilder elasticsearchQueryBuilder(ElasticsearchProperties props) {
        return new ElasticsearchQueryBuilder(props);
    }

    /**
     * A single application-specific {@link LogEventMapper} bean replaces the configuration-driven default; two or more are
     * ambiguous and fail startup (never silently pick one).
     */
    @Bean
    LogSource logSource(ElasticsearchClient client, ElasticsearchQueryBuilder queries,
            ObjectProvider<LogEventMapper> customMapper, ElasticsearchProperties props, MeterRegistry meters) {
        LogEventMapper mapper = customMapper.getIfAvailable(() -> new ElasticsearchLogMapper(props.fields(), props.mapping()));
        return new ElasticsearchLogReader(client, queries, mapper, props, meters);
    }

    @Bean("elasticsearchLiveHealthIndicator")
    ElasticsearchHealthIndicator elasticsearchHealthIndicator(ElasticsearchClient client) {
        return new ElasticsearchHealthIndicator(client);
    }
}
