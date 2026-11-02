package it.projectsrl.standpos.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import it.projectsrl.standpos.auth.AuthLookup;
import it.projectsrl.standpos.support.Browser;
import it.projectsrl.standpos.support.Fixtures;
import it.projectsrl.standpos.support.Fixtures.TenantFixture;
import it.projectsrl.standpos.support.IntegrationTest;
import it.projectsrl.standpos.venue.Venue;
import it.projectsrl.standpos.venue.VenueRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/** Self-service sign-up (docs/adr/0015-self-service-sign-up.md). */
class SignupIT extends IntegrationTest {

    static final String PASSWORD = "signup-password-1";
    static final String ACCEPTED = "{\"status\":\"accepted\"}";

    @Autowired
    AuthLookup lookup;

    @Autowired
    VenueRepository venues;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void availabilityIsPublic() throws Exception {
        browser().get("/api/v1/auth/signup")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.declarationsVersion").value(SignupService.DECLARATIONS_VERSION));
    }

    @Test
    void signUpCreatesTenantAndOwnerAndLogsIn() throws Exception {
        Browser browser = client();
        String email = newEmail();
        signUp(browser, "Oratorio Nuovo", email, PASSWORD, true, true)
                .andExpect(status().isAccepted())
                .andExpect(content().json(ACCEPTED, true));

        String me = browser.get("/api/v1/auth/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("USER"))
                .andExpect(jsonPath("$.tenant.name").value("Oratorio Nuovo"))
                .andExpect(jsonPath("$.user.displayName").value("Anna Rossi"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.memberships.length()").value(1))
                .andExpect(jsonPath("$.memberships[0].role").value("OWNER"))
                .andReturn().getResponse().getContentAsString();
        UUID tenantId = UUID.fromString(JsonPath.read(me, "$.tenant.id"));
        UUID ownerId = UUID.fromString(JsonPath.read(me, "$.user.id"));

        // No venue yet: the setup wizard creates them.
        browser.get("/api/v1/venues").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        browser.get("/api/v1/usage").andExpect(status().isOk()).andExpect(jsonPath("$.cap").value(2000));

        // The declarations are kept in the audit log, attributed to the owner.
        Map<String, Object> audit = fixtures.inTenant(tenantId, () -> jdbc.queryForMap("""
                select user_id, entity_id, after_data->'declarations'->>'version' as version,
                       (after_data->'declarations'->>'nonProfit')::boolean as non_profit
                from audit_log where action = 'tenant.signed_up'"""));
        assertThat(audit).containsEntry("user_id", ownerId)
                .containsEntry("entity_id", tenantId)
                .containsEntry("version", SignupService.DECLARATIONS_VERSION)
                .containsEntry("non_profit", true);

        // Email and password work for a normal login afterwards.
        Browser again = browser();
        again.postJson("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.id").value(tenantId.toString()));
    }

    @Test
    void registeredEmailGetsTheSameAnswerAndNothingIsCreated() throws Exception {
        TenantFixture existing = fixtures.tenant("Existing");
        Browser browser = client();
        signUp(browser, "Copia", existing.ownerEmail().toUpperCase(), "another-password-1", true, true)
                .andExpect(status().isAccepted())
                .andExpect(content().json(ACCEPTED, true));

        browser.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
        // The existing account is untouched: same password, still in its one tenant only.
        login(existing.ownerEmail(), "another-password-1").andExpect(status().isUnauthorized());
        login(existing.ownerEmail(), Fixtures.OWNER_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.id").value(existing.tenantId().toString()));
        assertThat(lookup.userMemberships(existing.ownerId()))
                .singleElement()
                .satisfies(m -> assertThat(m.tenantId()).isEqualTo(existing.tenantId()));
    }

    @Test
    void signUpEndsAnEarlierSession() throws Exception {
        TenantFixture existing = fixtures.tenant("Logged in");
        Browser browser = client();
        browser.postJson("/api/v1/auth/login",
                        "{\"email\":\"" + existing.ownerEmail() + "\",\"password\":\"" + Fixtures.OWNER_PASSWORD + "\"}")
                .andExpect(status().isOk());

        signUp(browser, "Copia", existing.ownerEmail(), PASSWORD, true, true).andExpect(status().isAccepted());

        browser.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    void declarationsAndPasswordRulesAreRequired() throws Exception {
        signUp(client(), "Senza dichiarazioni", newEmail(), PASSWORD, false, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation"))
                .andExpect(jsonPath("$.errors[?(@.field == 'nonProfit')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'nonFiscalAcknowledged')]").exists());
        signUp(client(), "Password corta", newEmail(), "short", true, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
        client().postJson("/api/v1/auth/signup", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'organisationName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'ownerName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
    }

    @Test
    void signUpsAreRateLimitedPerAddress() throws Exception {
        String address = randomAddress();
        for (int i = 0; i < 5; i++) {
            signUp(browser().withRemoteAddr(address), "Serie " + i, newEmail(), PASSWORD, true, true)
                    .andExpect(status().isAccepted());
        }
        signUp(browser().withRemoteAddr(address), "Una di troppo", newEmail(), PASSWORD, true, true)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("signup.rate_limited"));
        // Another address is not affected.
        signUp(client(), "Altro indirizzo", newEmail(), PASSWORD, true, true).andExpect(status().isAccepted());
    }

    @Test
    void signUpNeedsTheCsrfToken() throws Exception {
        signUp(client().withoutCsrfHeader(), "Senza CSRF", newEmail(), PASSWORD, true, true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf.invalid"));
    }

    @Test
    void theNewTenantSeesOnlyItsOwnData() throws Exception {
        TenantFixture other = fixtures.tenant("Other");
        Browser browser = client();
        signUp(browser, "Isolata", newEmail(), PASSWORD, true, true).andExpect(status().isAccepted());

        browser.get("/api/v1/venues").andExpect(jsonPath("$.length()").value(0));
        browser.get("/api/v1/users").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        browser.get("/api/v1/venues/" + other.venueId() + "/products").andExpect(status().isNotFound());

        String created = browser.postJson("/api/v1/venues", "{\"name\":\"Festa\",\"kind\":\"EVENT\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID venueId = UUID.fromString(JsonPath.read(created, "$.id"));
        List<Venue> visibleToOther = fixtures.inTenant(other.tenantId(), () -> venues.findAll());
        assertThat(visibleToOther).extracting(Venue::getId).containsExactly(other.venueId());
        assertThat(fixtures.inTenant(other.tenantId(), () -> venues.findById(venueId))).isEmpty();
    }

    private ResultActions signUp(Browser browser, String organisation, String email, String password,
            boolean nonProfit, boolean nonFiscal) throws Exception {
        return browser.postJson("/api/v1/auth/signup", """
                {"organisationName":"%s","ownerName":"Anna Rossi","email":"%s","password":"%s",
                 "nonProfit":%s,"nonFiscalAcknowledged":%s}""".formatted(organisation, email, password, nonProfit,
                nonFiscal));
    }

    private ResultActions login(String email, String password) throws Exception {
        return browser().postJson("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    /** A client with its own address, so the per-address sign-up limit never spills over between tests. */
    private Browser client() {
        return browser().withRemoteAddr(randomAddress());
    }

    private static String randomAddress() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    private static String newEmail() {
        return "signup-" + UUID.randomUUID() + "@standpos.test";
    }
}
