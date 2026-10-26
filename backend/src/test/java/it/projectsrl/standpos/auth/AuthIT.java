package it.projectsrl.standpos.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.projectsrl.standpos.device.DeviceKind;
import it.projectsrl.standpos.device.DeviceTokens;
import it.projectsrl.standpos.support.Browser;
import it.projectsrl.standpos.support.Fixtures;
import it.projectsrl.standpos.support.Fixtures.TenantFixture;
import it.projectsrl.standpos.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Acceptance criterion 4: login, lockout, CSRF, PIN rules and device revocation. */
class AuthIT extends IntegrationTest {

    TenantFixture tenant;

    @BeforeEach
    void tenant() {
        tenant = fixtures.tenant("Auth");
    }

    // Email and password

    @Test
    void ownerLogsInAndSeesTheirTenant() throws Exception {
        Browser browser = browser();
        login(browser, tenant.ownerEmail(), Fixtures.OWNER_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("USER"))
                .andExpect(jsonPath("$.tenant.id").value(tenant.tenantId().toString()))
                .andExpect(jsonPath("$.memberships[0].role").value("OWNER"));
        browser.get("/api/v1/auth/me").andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("USER"));
        browser.get("/api/v1/venues").andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(tenant.venueId().toString()));

        browser.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
        browser.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    void emailIsCaseInsensitive() throws Exception {
        login(browser(), tenant.ownerEmail().toUpperCase(), Fixtures.OWNER_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownEmailAreRejectedAlike() throws Exception {
        login(browser(), tenant.ownerEmail(), "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.invalid_credentials"));
        login(browser(), "nobody-" + UUID.randomUUID() + "@standpos.test", "whatever-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.invalid_credentials"));
    }

    @Test
    void repeatedFailuresLockTheAccount() throws Exception {
        for (int i = 1; i < 5; i++) {
            login(browser(), tenant.ownerEmail(), "wrong-" + i).andExpect(status().isUnauthorized());
        }
        login(browser(), tenant.ownerEmail(), "wrong-5")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("auth.locked"));
        login(browser(), tenant.ownerEmail(), Fixtures.OWNER_PASSWORD).andExpect(status().isLocked());
    }

    @Test
    void validationErrorsListTheFields() throws Exception {
        browser().postJson("/api/v1/auth/login", "{\"email\":\"not-an-email\",\"password\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation"))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }

    // CSRF

    @Test
    void stateChangingRequestsNeedTheCsrfToken() throws Exception {
        browser().withoutCsrfHeader()
                .postJson("/api/v1/auth/login", loginJson(tenant.ownerEmail(), Fixtures.OWNER_PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf.invalid"));

        Browser browser = browser();
        login(browser, tenant.ownerEmail(), Fixtures.OWNER_PASSWORD).andExpect(status().isOk());
        String json = "{\"name\":\"Bar\",\"sort\":0}";
        String url = "/api/v1/venues/" + tenant.venueId() + "/routes";
        browser.withoutCsrfHeader().postJson(url, json).andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsAreUnauthenticated() throws Exception {
        browser().get("/api/v1/venues")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    // Cashier PIN

    @Test
    void cashierLogsInWithPinOnARegisterOfTheirVenue() throws Exception {
        UUID cashier = fixtures.user(tenant, "Anna", null, null, "1357", tenant.venueId(), Role.CASHIER);
        Browser register = enrolledBrowser(tenant.venueId(), DeviceKind.REGISTER);

        register.get("/api/v1/register/cashiers").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.displayName == 'Anna')]").exists());
        pin(register, cashier, "1357")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("CASHIER"))
                .andExpect(jsonPath("$.device.kind").value("REGISTER"));
        register.get("/api/v1/register/catalogue").andExpect(status().isOk())
                .andExpect(jsonPath("$.venueId").value(tenant.venueId().toString()));
        // A cashier is not a manager.
        register.get("/api/v1/venues/" + tenant.venueId() + "/products").andExpect(status().isForbidden());
    }

    @Test
    void pinIsRejectedWithoutAnEnrolledDevice() throws Exception {
        UUID cashier = fixtures.user(tenant, "Bruno", null, null, "1357", tenant.venueId(), Role.CASHIER);
        pin(browser(), cashier, "1357").andExpect(status().isUnauthorized());

        Browser forged = browser();
        forged.perform(post("/api/v1/auth/pin")
                        .cookie(new jakarta.servlet.http.Cookie(DeviceTokens.COOKIE, DeviceTokens.newToken()))
                        .contentType("application/json")
                        .content(pinJson(cashier, "1357")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pinIsRejectedFromAnotherVenuesRegister() throws Exception {
        UUID otherVenue = fixtures.venue(tenant, "Sagra");
        UUID cashier = fixtures.user(tenant, "Carla", null, null, "1357", tenant.venueId(), Role.CASHIER);
        Browser register = enrolledBrowser(otherVenue, DeviceKind.REGISTER);
        pin(register, cashier, "1357")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.pin_not_allowed"));
    }

    @Test
    void pinIsRejectedFromAnotherTenantsRegister() throws Exception {
        TenantFixture other = fixtures.tenant("Other");
        UUID cashier = fixtures.user(tenant, "Dario", null, null, "1357", tenant.venueId(), Role.CASHIER);
        Browser register = enrolledBrowser(other, other.venueId(), DeviceKind.REGISTER);
        pin(register, cashier, "1357").andExpect(status().isUnauthorized());
    }

    @Test
    void pinIsRejectedFromANonRegisterDevice() throws Exception {
        UUID cashier = fixtures.user(tenant, "Elena", null, null, "1357", tenant.venueId(), Role.CASHIER);
        Browser kitchen = enrolledBrowser(tenant.venueId(), DeviceKind.KITCHEN);
        pin(kitchen, cashier, "1357").andExpect(status().isForbidden());
    }

    @Test
    void repeatedWrongPinsLockThePin() throws Exception {
        UUID cashier = fixtures.user(tenant, "Franco", null, null, "1357", tenant.venueId(), Role.CASHIER);
        Browser register = enrolledBrowser(tenant.venueId(), DeviceKind.REGISTER);
        for (int i = 0; i < 4; i++) {
            pin(register, cashier, "0000").andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("auth.invalid_pin"));
        }
        pin(register, cashier, "0000").andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("auth.pin_locked"));
        pin(register, cashier, "1357").andExpect(status().isLocked());
    }

    // Devices

    @Test
    void enrolmentCodeIsSingleUse() throws Exception {
        Browser owner = ownerBrowser();
        String code = owner.postJson("/api/v1/venues/" + tenant.venueId() + "/enrolment-codes", "{\"kind\":\"REGISTER\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isString())
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"code\":\"([A-Z0-9]+)\".*", "$1");

        Browser device = browser();
        device.postJson("/api/v1/devices/enrol", enrolJson(code.toLowerCase())).andExpect(status().isCreated());
        device.get("/api/v1/devices/current").andExpect(jsonPath("$.enrolled").value(true));
        browser().postJson("/api/v1/devices/enrol", enrolJson(code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("device.invalid_code"));
    }

    @Test
    void revokedDeviceStopsWorkingImmediately() throws Exception {
        UUID cashier = fixtures.user(tenant, "Gino", null, null, "1357", tenant.venueId(), Role.CASHIER);
        Browser register = enrolledBrowser(tenant.venueId(), DeviceKind.REGISTER);
        String deviceId = register.get("/api/v1/devices/current").andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1");
        pin(register, cashier, "1357").andExpect(status().isOk());
        register.get("/api/v1/register/catalogue").andExpect(status().isOk());

        ownerBrowser().perform(post("/api/v1/venues/" + tenant.venueId() + "/devices/" + deviceId + "/revoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt").isString());

        register.get("/api/v1/register/catalogue").andExpect(status().isUnauthorized());
        register.get("/api/v1/devices/current").andExpect(jsonPath("$.enrolled").value(false));
        pin(register, cashier, "1357").andExpect(status().isUnauthorized());
    }

    @Test
    void cashierSessionIsBoundToItsDevice() throws Exception {
        UUID cashier = fixtures.user(tenant, "Ivo", null, null, "1357", tenant.venueId(), Role.CASHIER);
        Browser register = enrolledBrowser(tenant.venueId(), DeviceKind.REGISTER);
        pin(register, cashier, "1357").andExpect(status().isOk());
        register.dropCookie(DeviceTokens.COOKIE);
        register.get("/api/v1/register/catalogue").andExpect(status().isUnauthorized());
    }

    // Helpers

    private Browser ownerBrowser() throws Exception {
        Browser browser = browser();
        login(browser, tenant.ownerEmail(), Fixtures.OWNER_PASSWORD).andExpect(status().isOk());
        return browser;
    }

    private Browser enrolledBrowser(UUID venueId, DeviceKind kind) throws Exception {
        return enrolledBrowser(tenant, venueId, kind);
    }

    private Browser enrolledBrowser(TenantFixture t, UUID venueId, DeviceKind kind) throws Exception {
        return browser().withCookie(DeviceTokens.COOKIE, fixtures.enrol(t, venueId, kind));
    }

    private static org.springframework.test.web.servlet.ResultActions login(Browser browser, String email,
            String password) throws Exception {
        return browser.postJson("/api/v1/auth/login", loginJson(email, password));
    }

    private static org.springframework.test.web.servlet.ResultActions pin(Browser browser, UUID userId, String pin)
            throws Exception {
        return browser.postJson("/api/v1/auth/pin", pinJson(userId, pin));
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String pinJson(UUID userId, String pin) {
        return "{\"userId\":\"" + userId + "\",\"pin\":\"" + pin + "\"}";
    }

    private static String enrolJson(String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"Cassa 1\"}";
    }
}
