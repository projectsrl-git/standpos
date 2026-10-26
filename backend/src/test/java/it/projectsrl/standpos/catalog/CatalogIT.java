package it.projectsrl.standpos.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import it.projectsrl.standpos.audit.AuditLog;
import it.projectsrl.standpos.audit.AuditLogRepository;
import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.device.DeviceKind;
import it.projectsrl.standpos.device.DeviceTokens;
import it.projectsrl.standpos.support.Browser;
import it.projectsrl.standpos.support.Fixtures;
import it.projectsrl.standpos.support.Fixtures.TenantFixture;
import it.projectsrl.standpos.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Catalogue CRUD, venue-scoped authorisation, audit of price and role changes, and the register grid. */
class CatalogIT extends IntegrationTest {

    @Autowired
    AuditLogRepository auditLogs;

    TenantFixture tenant;
    Browser owner;
    String base;

    @BeforeEach
    void ownerLoggedIn() throws Exception {
        tenant = fixtures.tenant("Catalogue");
        owner = login(tenant.ownerEmail(), Fixtures.OWNER_PASSWORD);
        base = "/api/v1/venues/" + tenant.venueId();
    }

    @Test
    void ownerBuildsACatalogueAndTheRegisterShowsIt() throws Exception {
        String routeId = id(owner.postJson(base + "/routes", "{\"name\":\"Bar\",\"sort\":1}")
                .andExpect(status().isCreated()));
        String categoryId = id(owner.postJson(base + "/categories",
                        "{\"name\":\"Bibite\",\"colour\":\"#1e88e5\",\"sort\":0,\"defaultRouteId\":\"" + routeId + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.colour").value("#1E88E5")));
        owner.postJson(base + "/products", product(categoryId, "Acqua", 100, true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceCents").value(100));
        owner.postJson(base + "/products", product(categoryId, "Spritz (off)", 400, false))
                .andExpect(status().isCreated());

        owner.get(base + "/products").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        Browser register = browser().withCookie(DeviceTokens.COOKIE,
                fixtures.enrol(tenant, tenant.venueId(), DeviceKind.REGISTER));
        register.postJson("/api/v1/auth/pin", "{\"userId\":\"" + tenant.ownerId() + "\",\"pin\":\"" + Fixtures.PIN + "\"}")
                .andExpect(status().isOk());
        register.get("/api/v1/register/catalogue")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].name").value("Bibite"))
                .andExpect(jsonPath("$.categories[0].products.length()").value(1))
                .andExpect(jsonPath("$.categories[0].products[0].name").value("Acqua"));
    }

    @Test
    void priceChangesAreAudited() throws Exception {
        String categoryId = id(owner.postJson(base + "/categories", "{\"name\":\"Cibo\",\"colour\":\"#E53935\",\"sort\":0}"));
        String productId = id(owner.postJson(base + "/products", product(categoryId, "Panino", 450, true)));

        owner.perform(put(base + "/products/" + productId).contentType(MediaType.APPLICATION_JSON)
                        .content(product(categoryId, "Panino", 500, true)))
                .andExpect(status().isOk());
        owner.perform(put(base + "/products/" + productId).contentType(MediaType.APPLICATION_JSON)
                        .content(product(categoryId, "Panino con salamella", 500, true)))
                .andExpect(status().isOk());

        List<AuditLog> entries = fixtures.inTenant(tenant.tenantId(),
                () -> auditLogs.findAllByEntityIdOrderByCreatedAt(UUID.fromString(productId)));
        assertThat(entries).extracting(AuditLog::getAction).containsExactly("product.created", "product.price_changed");
        AuditLog change = entries.get(1);
        assertThat(change.getUserId()).isEqualTo(tenant.ownerId());
        assertThat(change.getBeforeData()).contains("450");
        assertThat(change.getAfterData()).contains("500");
    }

    @Test
    void roleChangesAreAudited() throws Exception {
        String body = owner.postJson("/api/v1/users", "{\"displayName\":\"Marta\",\"pin\":\"1122\",\"venueId\":\""
                        + tenant.venueId() + "\",\"role\":\"CASHIER\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasPin").value(true))
                .andReturn().getResponse().getContentAsString();
        String userId = JsonPath.read(body, "$.id");
        String membershipId = JsonPath.read(body, "$.memberships[0].id");

        owner.postJson("/api/v1/users/" + userId + "/memberships",
                        "{\"venueId\":\"" + tenant.venueId() + "\",\"role\":\"MANAGER\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberships.length()").value(2));
        owner.perform(delete("/api/v1/users/" + userId + "/memberships/" + membershipId)).andExpect(status().isOk());

        List<AuditLog> entries = fixtures.inTenant(tenant.tenantId(),
                () -> auditLogs.findAll().stream().filter(a -> a.getEntityType().equals("membership")).toList());
        assertThat(entries).extracting(AuditLog::getAction)
                .contains("membership.created", "membership.deleted");
        assertThat(entries).filteredOn(a -> a.getAction().equals("membership.deleted"))
                .singleElement().satisfies(a -> assertThat(a.getBeforeData()).contains("CASHIER"));
    }

    @Test
    void managersAreLimitedToTheirVenue() throws Exception {
        UUID otherVenue = fixtures.venue(tenant, "Sagra");
        String email = "manager-" + UUID.randomUUID() + "@standpos.test";
        fixtures.user(tenant, "Manager", email, "manager-password-1", null, otherVenue, Role.MANAGER);
        Browser manager = login(email, "manager-password-1");

        manager.get("/api/v1/venues/" + otherVenue + "/products").andExpect(status().isOk());
        manager.get(base + "/products").andExpect(status().isForbidden());
        manager.get("/api/v1/users").andExpect(status().isForbidden());
        manager.get("/api/v1/venues").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(otherVenue.toString()));
    }

    @Test
    void anotherTenantsVenueIsInvisible() throws Exception {
        TenantFixture other = fixtures.tenant("Other");
        owner.get("/api/v1/venues/" + other.venueId() + "/products")
                .andExpect(status().isNotFound());
        owner.postJson("/api/v1/venues/" + other.venueId() + "/routes", "{\"name\":\"X\",\"sort\":0}")
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidInputIsReportedPerField() throws Exception {
        owner.postJson(base + "/categories", "{\"name\":\"\",\"colour\":\"red\",\"sort\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'colour')]").exists());
        owner.postJson(base + "/products", "{\"name\":\"X\",\"priceCents\":-1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'priceCents')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'categoryId')]").exists());
    }

    @Test
    void nonEmptyCategoryCannotBeDeleted() throws Exception {
        String categoryId = id(owner.postJson(base + "/categories", "{\"name\":\"Dolci\",\"colour\":\"#8E24AA\",\"sort\":0}"));
        String productId = id(owner.postJson(base + "/products", product(categoryId, "Torta", 250, true)));
        owner.perform(delete(base + "/categories/" + categoryId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("catalog.category_not_empty"));
        owner.perform(delete(base + "/products/" + productId)).andExpect(status().isNoContent());
        owner.perform(delete(base + "/categories/" + categoryId)).andExpect(status().isNoContent());
    }

    private Browser login(String email, String password) throws Exception {
        Browser browser = browser();
        browser.postJson("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .andExpect(status().isOk());
        return browser;
    }

    private static String product(String categoryId, String name, long priceCents, boolean active) {
        return "{\"categoryId\":\"" + categoryId + "\",\"name\":\"" + name + "\",\"priceCents\":" + priceCents
                + ",\"active\":" + active + ",\"trackStock\":false,\"soldOut\":false,\"sort\":0}";
    }

    private static String id(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
