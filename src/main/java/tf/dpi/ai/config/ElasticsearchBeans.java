package tf.dpi.ai.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tf.dpi.ai.elasticsearch.ElasticsearchHealthIndicator;
import tf.dpi.ai.elasticsearch.ElasticsearchLogMapper;
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

    @Bean
    ElasticsearchLogMapper elasticsearchLogMapper(ElasticsearchProperties props) {
        return new ElasticsearchLogMapper(props.fields());
    }

    @Bean
    LogSource logSource(ElasticsearchClient client, ElasticsearchQueryBuilder queries, ElasticsearchLogMapper mapper,
            ElasticsearchProperties props, MeterRegistry meters) {
        return new ElasticsearchLogReader(client, queries, mapper, props, meters);
    }

    @Bean("elasticsearchLiveHealthIndicator")
    ElasticsearchHealthIndicator elasticsearchHealthIndicator(ElasticsearchClient client) {
        return new ElasticsearchHealthIndicator(client);
    }
}
