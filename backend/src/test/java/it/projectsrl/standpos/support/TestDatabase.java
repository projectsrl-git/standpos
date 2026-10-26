package it.projectsrl.standpos.support;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The PostgreSQL 17 database used by integration tests.
 *
 * <p>By default a Testcontainers container, initialised with the same role script as Docker Compose
 * ({@code deploy/postgres/initdb/10-standpos-roles.sh}). When {@code STANDPOS_TEST_JDBC_URL} is set, an existing
 * database is used instead (for machines without Docker); it must have been initialised with the same roles, see
 * docs/development.md.
 */
public final class TestDatabase {

    public record Coordinates(String url, String ownerPassword, String appPassword) {}

    private static final String OWNER_PASSWORD = "test-owner-password";
    private static final String APP_PASSWORD = "test-app-password";

    private static Coordinates coordinates;

    private TestDatabase() {}

    public static synchronized Coordinates get() {
        if (coordinates == null) {
            String external = System.getenv("STANDPOS_TEST_JDBC_URL");
            coordinates = external != null && !external.isBlank()
                    ? new Coordinates(external, env("STANDPOS_TEST_OWNER_PASSWORD", OWNER_PASSWORD),
                            env("STANDPOS_TEST_APP_PASSWORD", APP_PASSWORD))
                    : startContainer();
        }
        return coordinates;
    }

    /** Points a test context at the database: runtime as {@code standpos_app}, Flyway as {@code standpos_owner}. */
    public static void register(DynamicPropertyRegistry registry) {
        Coordinates db = get();
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", () -> "standpos_app");
        registry.add("spring.datasource.password", db::appPassword);
        registry.add("spring.flyway.url", db::url);
        registry.add("spring.flyway.user", () -> "standpos_owner");
        registry.add("spring.flyway.password", db::ownerPassword);
    }

    @SuppressWarnings("resource") // one container for the whole test JVM, stopped by Testcontainers' reaper
    private static Coordinates startContainer() {
        Path script = Path.of("..", "deploy", "postgres", "initdb", "10-standpos-roles.sh").toAbsolutePath().normalize();
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("standpos")
                .withEnv("STANDPOS_OWNER_PASSWORD", OWNER_PASSWORD)
                .withEnv("STANDPOS_APP_PASSWORD", APP_PASSWORD)
                .withCopyFileToContainer(MountableFile.forHostPath(script, 0755),
                        "/docker-entrypoint-initdb.d/10-standpos-roles.sh");
        container.start();
        return new Coordinates(container.getJdbcUrl(), OWNER_PASSWORD, APP_PASSWORD);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
