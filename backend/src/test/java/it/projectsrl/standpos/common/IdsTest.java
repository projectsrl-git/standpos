package it.projectsrl.standpos.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersion7Rfc9562Ids() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheCurrentTimeInTheFirst48Bits() {
        long before = System.currentTimeMillis();
        UUID id = Ids.newId();
        long after = System.currentTimeMillis();
        long millis = id.getMostSignificantBits() >>> 16;
        assertThat(millis).isBetween(before, after);
    }

    @Test
    void doesNotRepeat() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId());
        }
        assertThat(ids).hasSize(10_000);
    }
}
