package tf.dpi.ai.support;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Migrated PostgreSQL + pgvector for integration tests. Uses DPI_TEST_JDBC_URL (DPI_TEST_JDBC_USER / _PASSWORD) when set
 * - the public schema is DROPPED and re-migrated, so only point it at a disposable database - otherwise a Testcontainers
 * pgvector container when Docker is available. {@link #available()} is false when neither exists (tests skip).
 */
public final class TestDatabase {

    private static DataSource shared;
    private static PostgreSQLContainer container;

    private TestDatabase() {
    }

    public static boolean available() {
        if (System.getenv("DPI_TEST_JDBC_URL") != null) {
            return true;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static synchronized DataSource migrated() {
        if (shared != null) {
            return shared;
        }
        DriverManagerDataSource ds;
        String url = System.getenv("DPI_TEST_JDBC_URL");
        if (url != null) {
            ds = new DriverManagerDataSource(url, System.getenv().getOrDefault("DPI_TEST_JDBC_USER", "postgres"),
                    System.getenv().getOrDefault("DPI_TEST_JDBC_PASSWORD", ""));
            Flyway.configure().dataSource(ds).cleanDisabled(false).load().clean();
        } else {
            container = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
            container.start();
            ds = new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
        }
        Flyway.configure().dataSource(ds).load().migrate();
        shared = ds;
        return ds;
    }
}
