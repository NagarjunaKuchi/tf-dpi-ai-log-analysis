package tf.dpi.ai.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Health via a read-only cluster ping (HEAD /). Reports only reachability, never cluster details. */
public class ElasticsearchHealthIndicator implements HealthIndicator {

    private final ElasticsearchClient client;

    public ElasticsearchHealthIndicator(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            return client.ping().value() ? Health.up().build() : Health.down().build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
