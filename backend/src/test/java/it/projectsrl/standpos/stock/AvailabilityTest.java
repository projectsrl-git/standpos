package it.projectsrl.standpos.stock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AvailabilityTest {

    @Test
    void trackedProductsSellOutAtZeroUnlessNegativeStockIsAllowed() {
        assertThat(Availability.of(true, 0, false, null, false).soldOut()).isTrue();
        assertThat(Availability.of(true, 0, false, null, true).soldOut()).isFalse();
        assertThat(Availability.of(true, 3, false, null, false).soldOut()).isFalse();
    }

    @Test
    void theManualFlagWinsAndUntrackedProductsHaveNoQuantity() {
        assertThat(Availability.of(true, 10, true, null, false).soldOut()).isTrue();
        Availability coffee = Availability.of(false, null, false, 5, false);
        assertThat(coffee.soldOut()).isFalse();
        assertThat(coffee.low()).isFalse();
        assertThat(coffee.onHand()).isNull();
    }

    @Test
    void lowAtOrBelowTheThreshold() {
        assertThat(Availability.of(true, 5, false, 5, false).low()).isTrue();
        assertThat(Availability.of(true, 6, false, 5, false).low()).isFalse();
        assertThat(Availability.of(true, 6, false, null, false).low()).isFalse();
    }
}
