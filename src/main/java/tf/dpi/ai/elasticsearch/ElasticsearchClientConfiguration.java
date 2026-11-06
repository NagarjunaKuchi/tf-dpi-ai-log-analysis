package tf.dpi.ai.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Builds the Elasticsearch client for READ-ONLY use. TLS trust comes from an optional CA file (otherwise the JVM
 * trust store); authentication is an API key or basic credentials supplied through the environment.
 */
@Configuration
public class ElasticsearchClientConfiguration {

    @Bean(destroyMethod = "close")
    RestClient elasticsearchRestClient(ElasticsearchProperties props) {
        HttpHost[] hosts = props.uris().stream().map(ElasticsearchClientConfiguration::toHost).toArray(HttpHost[]::new);
        var builder = RestClient.builder(hosts);

        if (StringUtils.hasText(props.apiKey())) {
            builder.setDefaultHeaders(new Header[] {new BasicHeader("Authorization", "ApiKey " + props.apiKey())});
        } else if (StringUtils.hasText(props.username())) {
            String token = Base64.getEncoder().encodeToString(
                    (props.username() + ":" + props.password()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            builder.setDefaultHeaders(new Header[] {new BasicHeader("Authorization", "Basic " + token)});
        }

        SSLContext sslContext = sslContext(props);
        builder.setHttpClientConfigCallback(http -> {
            if (sslContext != null) {
                http.setSSLContext(sslContext);
            }
            return http;
        });
        builder.setRequestConfigCallback(rc -> rc
                .setConnectTimeout((int) props.connectTimeout().toMillis())
                .setSocketTimeout((int) props.socketTimeout().toMillis()));
        return builder.build();
    }

    @Bean
    ElasticsearchClient elasticsearchClient(RestClient restClient, ObjectMapper objectMapper) {
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
        return new ElasticsearchClient(transport);
    }

    private static HttpHost toHost(String uri) {
        java.net.URI parsed = java.net.URI.create(uri);
        String scheme = parsed.getScheme() == null ? "https" : parsed.getScheme();
        int port = parsed.getPort() > 0 ? parsed.getPort() : ("https".equals(scheme) ? 443 : 9200);
        if (parsed.getHost() == null) {
            throw new IllegalArgumentException("Invalid Elasticsearch URI (host missing)");
        }
        return new HttpHost(parsed.getHost(), port, scheme);
    }

    private static SSLContext sslContext(ElasticsearchProperties props) {
        if (!StringUtils.hasText(props.caCertificatePath())) {
            return null;
        }
        try (InputStream in = Files.newInputStream(Path.of(props.caCertificatePath()))) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int i = 0;
            for (Certificate cert : factory.generateCertificates(in)) {
                trust.setCertificateEntry("es-ca-" + i++, cert);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load Elasticsearch CA certificate", e);
        }
    }
}
