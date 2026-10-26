package it.projectsrl.standpos.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base of the integration tests: the full application against PostgreSQL 17, connected as {@code standpos_app}
 * (runtime) with Flyway as {@code standpos_owner}. All subclasses share one Spring context and one database; each
 * test creates its own tenants, so tests never depend on each other's data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({Fixtures.class, SalesFixtures.class, TestClockConfig.class})
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected Fixtures fixtures;

    @Autowired
    protected SalesFixtures sales;

    @Autowired
    protected MutableClock clock;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    protected Browser browser() {
        return new Browser(mvc);
    }
}
