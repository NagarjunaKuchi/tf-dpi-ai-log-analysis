package tf.dpi.ai.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Proves the documented ways of configuring a new application actually bind: environment variables and YAML-style keys. */
class MappingBindingTest {

    /** Binds the way the application does: through an Environment with attached configuration property sources. */
    private static StandardEnvironment environmentWith(org.springframework.core.env.PropertySource<?> source) {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(source);
        ConfigurationPropertySources.attach(env);
        return env;
    }

    @Test
    void bindsFromEnvironmentVariables() {
        var env = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>of(
                "DPI_ELASTICSEARCH_URIS", "https://es.example:9200",
                "DPI_ELASTICSEARCH_FIELDS_SERVICE", "kubernetes.container.name",
                "DPI_ELASTICSEARCH_FIELDS_LEVEL", "log.level",
                "DPI_ELASTICSEARCH_MAPPING_TIMESTAMPFORMATS_0", "iso",
                "DPI_ELASTICSEARCH_MAPPING_TIMESTAMPFORMATS_1", "dd/MMM/yyyy:HH:mm:ss Z",
                "DPI_ELASTICSEARCH_MAPPING_TIMESTAMPZONE", "Asia/Kolkata",
                "DPI_ELASTICSEARCH_MAPPING_LEVELALIASES_E", "ERROR",
                "DPI_ELASTICSEARCH_MAPPING_EXCEPTIONPATTERN", "^(\\w+Error)"));
        ElasticsearchProperties p = Binder.get(environmentWith(env)).bind("dpi.elasticsearch", ElasticsearchProperties.class).get();
        assertThat(p.fields().service()).isEqualTo("kubernetes.container.name");
        assertThat(p.fields().level()).isEqualTo("log.level");
        assertThat(p.fields().message()).isEqualTo("message"); // untouched defaults stay
        assertThat(p.mapping().timestampFormats()).containsExactly("iso", "dd/MMM/yyyy:HH:mm:ss Z");
        assertThat(p.mapping().timestampZone()).isEqualTo("Asia/Kolkata");
        assertThat(p.mapping().levelAliases()).containsEntry("e", "ERROR"); // env names lose case: the mapper upper-cases keys
        assertThat(p.mapping().exceptionPattern()).isEqualTo("^(\\w+Error)");
    }

    @Test
    void defaultsApplyWhenNothingIsConfigured() {
        var props = new MapPropertySource("yaml", Map.<String, Object>of("dpi.elasticsearch.uris[0]", "https://es:9200"));
        ElasticsearchProperties p = Binder.get(environmentWith(props)).bind("dpi.elasticsearch", ElasticsearchProperties.class).get();
        assertThat(p.mapping().timestampFormats()).containsExactly("iso", "epoch_millis");
        assertThat(p.mapping().timestampZone()).isEqualTo("UTC");
        assertThat(p.mapping().levelAliases()).isEmpty();
        assertThat(p.mapping().exceptionPattern()).isEmpty();
    }
}
