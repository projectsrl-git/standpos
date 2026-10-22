package it.projectsrl.standpos.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class DeviceTokensTest {

    @Test
    void tokensCarry256RandomBits() {
        String token = DeviceTokens.newToken();
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(DeviceTokens.newToken()).isNotEqualTo(token);
    }

    @Test
    void onlyTheSha256HashIsStored() {
        assertThat(DeviceTokens.hash("abc")).hasSize(32);
        assertThat(DeviceTokens.hash("abc")).isEqualTo(DeviceTokens.hash("abc"));
    }

    @Test
    void enrolmentCodesHaveEightUnambiguousCharacters() {
        for (int i = 0; i < 1_000; i++) {
            String code = DeviceTokens.newEnrolmentCode();
            assertThat(code).hasSize(8).matches("[A-HJKMNP-Z2-9]{8}").doesNotContain("0", "O", "1", "I", "L");
        }
    }

    @Test
    void typedCodesAreNormalised() {
        assertThat(DeviceTokens.normaliseCode(" abcd-efgh ")).isEqualTo("ABCDEFGH");
    }
}
