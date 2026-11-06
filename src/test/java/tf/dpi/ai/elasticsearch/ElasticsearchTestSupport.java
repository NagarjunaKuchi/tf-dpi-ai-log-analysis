package tf.dpi.ai.elasticsearch;

import java.util.List;

final class ElasticsearchTestSupport {

    private ElasticsearchTestSupport() {
    }

    static ElasticsearchProperties props() {
        return new ElasticsearchProperties(List.of("https://es.example.internal:9200"), "logstash-*", null, null, null,
                null, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30), 100, 2,
                java.time.Duration.ofMillis(10), java.time.Duration.ofMillis(40), "_id",
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(30), 50,
                new ElasticsearchProperties.Fields("@timestamp", "service", "host", "level", "message", "traceId",
                        "errorCode", "exceptionType", "stack_trace"));
    }
}
