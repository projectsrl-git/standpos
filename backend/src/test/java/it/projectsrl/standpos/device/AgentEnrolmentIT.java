package it.projectsrl.standpos.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import it.projectsrl.standpos.support.Browser;
import it.projectsrl.standpos.support.Fixtures.TenantFixture;
import it.projectsrl.standpos.support.IntegrationTest;
import it.projectsrl.standpos.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Acceptance criterion 11: print agents enrol on their own endpoint without CSRF and receive no cookie; browser
 * enrolment still requires CSRF and rejects print agent codes (without burning them).
 */
class AgentEnrolmentIT extends IntegrationTest {

    @Autowired
    DeviceService devices;

    TenantFixture tenant;

    @BeforeEach
    void tenant() {
        tenant = fixtures.tenant("Agents");
    }

    @Test
    void agentEnrolsWithoutCsrfAndGetsNoCookie() throws Exception {
        String code = code(DeviceKind.PRINT_AGENT);

        // A bare request: no cookie, no CSRF header.
        MockHttpServletResponse response = mvc.perform(post("/api/v1/agents/enrol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrolJson(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.device.kind").value("PRINT_AGENT"))
                .andExpect(jsonPath("$.token").isString())
                .andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(response.getCookies()).isEmpty();
        String token = JsonPath.read(response.getContentAsString(), "$.token");

        MockHttpServletResponse current = mvc.perform(get("/api/v1/devices/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.enrolled").value(true))
                .andExpect(jsonPath("$.kind").value("PRINT_AGENT"))
                .andReturn().getResponse();
        assertThat(current.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void browserEnrolmentRejectsAgentCodesWithoutBurningThem() throws Exception {
        String code = code(DeviceKind.PRINT_AGENT);
        browser().postJson("/api/v1/devices/enrol", enrolJson(code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("device.agent_code"));

        mvc.perform(post("/api/v1/agents/enrol").contentType(MediaType.APPLICATION_JSON).content(enrolJson(code)))
                .andExpect(status().isCreated());
    }

    @Test
    void agentEndpointRejectsBrowserCodes() throws Exception {
        String code = code(DeviceKind.REGISTER);
        mvc.perform(post("/api/v1/agents/enrol").contentType(MediaType.APPLICATION_JSON).content(enrolJson(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("device.not_agent_code"));

        Browser register = browser();
        register.postJson("/api/v1/devices/enrol", enrolJson(code)).andExpect(status().isCreated());
        assertThat(register.cookie(DeviceTokens.COOKIE)).isNotBlank();
    }

    @Test
    void browserEnrolmentStillRequiresCsrf() throws Exception {
        String code = code(DeviceKind.REGISTER);
        browser().withoutCsrfHeader().postJson("/api/v1/devices/enrol", enrolJson(code))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf.invalid"));
    }

    private String code(DeviceKind kind) {
        return TenantContext.callAs(tenant.tenantId(),
                () -> devices.createEnrolmentCode(tenant.venueId(), kind, tenant.ownerId()).getCode());
    }

    private static String enrolJson(String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"Agente cassa 1\"}";
    }
}
