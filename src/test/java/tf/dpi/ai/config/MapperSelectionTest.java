package tf.dpi.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tf.dpi.ai.elasticsearch.ElasticsearchLogMapper;
import tf.dpi.ai.elasticsearch.ElasticsearchProperties;
import tf.dpi.ai.elasticsearch.LogEventMapper;
import tf.dpi.ai.elasticsearch.LogSource;

/** Which {@link LogEventMapper} the log source uses: the configurable default, one custom bean, or a startup failure. */
class MapperSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ElasticsearchBeans.class)
            .withBean(ElasticsearchClient.class, () -> mock(ElasticsearchClient.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(ElasticsearchProperties.class, () -> new ElasticsearchProperties(List.of("https://es:9200"), "logstash-*",
                    null, null, null, null, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30), 100, 2,
                    java.time.Duration.ofMillis(10), java.time.Duration.ofMillis(40), "_id", java.time.Duration.ofSeconds(30),
                    java.time.Duration.ofSeconds(30), 50,
                    new ElasticsearchProperties.Fields("@timestamp", "service", "host", "level", "message", "traceId",
                            "errorCode", "exceptionType", "stack_trace"),
                    ElasticsearchProperties.Mapping.defaults(), ".keyword"));

    private static LogEventMapper mapperOf(LogSource source) throws Exception {
        Field f = source.getClass().getDeclaredField("mapper");
        f.setAccessible(true);
        return (LogEventMapper) f.get(source);
    }

    @Test
    void usesTheConfigurableDefaultWhenNoCustomMapperExists() {
        runner.run(ctx -> assertThat(mapperOf(ctx.getBean(LogSource.class))).isInstanceOf(ElasticsearchLogMapper.class));
    }

    @Test
    void aSingleCustomMapperReplacesTheDefault() {
        LogEventMapper custom = (id, index, src) -> null;
        runner.withBean("appMapper", LogEventMapper.class, () -> custom)
                .run(ctx -> assertThat(mapperOf(ctx.getBean(LogSource.class))).isSameAs(custom));
    }

    @Test
    void twoCustomMappersFailStartupInsteadOfPickingOne() {
        runner.withBean("one", LogEventMapper.class, () -> (id, i, s) -> null)
                .withBean("two", LogEventMapper.class, () -> (id, i, s) -> null)
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
