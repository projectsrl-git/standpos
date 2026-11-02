package it.projectsrl.standpos.tenant;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.projectsrl.standpos.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With the switch off (the default outside dev and demo) sign-up does not exist. */
@TestPropertySource(properties = "standpos.signup.enabled=false")
class SignupDisabledIT extends IntegrationTest {

    @Test
    void signUpIsUnavailable() throws Exception {
        browser().get("/api/v1/auth/signup").andExpect(status().isNotFound());
        browser().postJson("/api/v1/auth/signup", """
                        {"organisationName":"Spenta","ownerName":"Anna","email":"off@standpos.test",
                         "password":"signup-password-1","nonProfit":true,"nonFiscalAcknowledged":true}""")
                .andExpect(status().isNotFound());
    }
}
