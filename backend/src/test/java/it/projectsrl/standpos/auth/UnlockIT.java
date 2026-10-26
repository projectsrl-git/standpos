package it.projectsrl.standpos.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.projectsrl.standpos.audit.AuditLog;
import it.projectsrl.standpos.audit.AuditLogRepository;
import it.projectsrl.standpos.device.DeviceKind;
import it.projectsrl.standpos.device.DeviceTokens;
import it.projectsrl.standpos.support.Browser;
import it.projectsrl.standpos.support.Fixtures;
import it.projectsrl.standpos.support.Fixtures.TenantFixture;
import it.projectsrl.standpos.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Acceptance criterion 10: owners unlock accounts and PINs, a venue's manager unlocks the PIN of people working at
 * that venue, a manager of another venue cannot; every unlock is audited.
 */
class UnlockIT extends IntegrationTest {

    @Autowired
    AuditLogRepository auditLogs;

    TenantFixture tenant;
    UUID otherVenue;
    UUID cashier;
    String managerEmail;
    String otherManagerEmail;
    String register;

    @BeforeEach
    void people() {
        tenant = fixtures.tenant("Unlock");
        otherVenue = fixtures.venue(tenant, "Sagra");
        cashier = fixtures.user(tenant, "Gina", null, null, "1357", tenant.venueId(), Role.CASHIER);
        managerEmail = "manager-" + UUID.randomUUID() + "@standpos.test";
        fixtures.user(tenant, "Mara", managerEmail, "manager-password-1", null, tenant.venueId(), Role.MANAGER);
        otherManagerEmail = "other-" + UUID.randomUUID() + "@standpos.test";
        fixtures.user(tenant, "Otto", otherManagerEmail, "manager-password-1", null, otherVenue, Role.MANAGER);
        register = fixtures.enrol(tenant, tenant.venueId(), DeviceKind.REGISTER);
    }

    @Test
    void venueManagerUnlocksAPinEarly() throws Exception {
        lockPin();
        Browser manager = login(managerEmail, "manager-password-1");
        String url = "/api/v1/venues/" + tenant.venueId() + "/users";
        manager.get(url)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId == '" + cashier + "')].pinLockedUntil").isNotEmpty());

        manager.postJson(url + "/" + cashier + "/unlock-pin", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinLockedUntil").doesNotExist());
        pin("1357").andExpect(status().isOk());
        assertAudited(1);
    }

    @Test
    void aManagerOfAnotherVenueCannotUnlock() throws Exception {
        lockPin();
        Browser other = login(otherManagerEmail, "manager-password-1");
        other.postJson("/api/v1/venues/" + tenant.venueId() + "/users/" + cashier + "/unlock-pin", "{}")
                .andExpect(status().isForbidden());
        other.postJson("/api/v1/venues/" + otherVenue + "/users/" + cashier + "/unlock-pin", "{}")
                .andExpect(status().isNotFound());
        other.postJson("/api/v1/users/" + cashier + "/unlock", "{}").andExpect(status().isForbidden());
        pin("1357").andExpect(status().isLocked());
        assertAudited(0);
    }

    @Test
    void ownerUnlocksTheAccountAndThePin() throws Exception {
        lockPin();
        for (int i = 0; i < 5; i++) {
            browser().postJson("/api/v1/auth/login",
                    "{\"email\":\"" + managerEmail + "\",\"password\":\"wrong-password-0\"}");
        }
        browser().postJson("/api/v1/auth/login",
                        "{\"email\":\"" + managerEmail + "\",\"password\":\"manager-password-1\"}")
                .andExpect(status().isLocked());

        Browser owner = login(tenant.ownerEmail(), Fixtures.OWNER_PASSWORD);
        owner.get("/api/v1/users")
                .andExpect(jsonPath("$[?(@.displayName == 'Mara')].lockedUntil").isNotEmpty());
        java.util.List<String> ids = com.jayway.jsonpath.JsonPath.read(owner.get("/api/v1/users")
                .andReturn().getResponse().getContentAsString(), "$[?(@.displayName == 'Mara')].id");
        String manager = ids.getFirst();
        owner.postJson("/api/v1/users/" + manager + "/unlock", "{\"account\":true,\"pin\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedUntil").doesNotExist());
        login(managerEmail, "manager-password-1");

        owner.postJson("/api/v1/users/" + cashier + "/unlock", "{}").andExpect(status().isOk());
        pin("1357").andExpect(status().isOk());
        assertThat(fixtures.inTenant(tenant.tenantId(), () -> auditLogs.findAll()).stream()
                .filter(a -> a.getAction().equals("user.unlocked"))
                .map(AuditLog::getUserId))
                .hasSize(2)
                .containsOnly(tenant.ownerId());
    }

    private void lockPin() throws Exception {
        for (int i = 0; i < 5; i++) {
            pin("0000");
        }
        pin("1357").andExpect(status().isLocked());
    }

    private org.springframework.test.web.servlet.ResultActions pin(String pin) throws Exception {
        return browser().withCookie(DeviceTokens.COOKIE, register)
                .postJson("/api/v1/auth/pin", "{\"userId\":\"" + cashier + "\",\"pin\":\"" + pin + "\"}");
    }

    private void assertAudited(int count) {
        assertThat(fixtures.inTenant(tenant.tenantId(), () -> auditLogs.findAllByEntityIdOrderByCreatedAt(cashier)))
                .filteredOn(a -> a.getAction().equals("user.unlocked"))
                .hasSize(count);
    }

    private Browser login(String email, String password) throws Exception {
        Browser browser = browser();
        browser.postJson("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .andExpect(status().isOk());
        return browser;
    }
}
