package it.projectsrl.standpos.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ValuationTest {

    @Test
    void outgoingTakesAProportionalShareRoundedHalfUp() {
        // 3 units worth 100 cents: one unit takes 33, two take 67, all three take 100.
        assertThat(Valuation.outgoing(3, 100, 1)).isEqualTo(33);
        assertThat(Valuation.outgoing(3, 100, 2)).isEqualTo(67);
        assertThat(Valuation.outgoing(3, 100, 3)).isEqualTo(100);
        assertThat(Valuation.outgoing(2, 101, 1)).isEqualTo(51);
    }

    @Test
    void theLastUnitsTakeWhatIsLeftSoTheValueEndsAtZero() {
        Random random = new Random(42);
        for (int round = 0; round < 1_000; round++) {
            int onHand = 1 + random.nextInt(200);
            long value = random.nextInt(1_000_000);
            while (onHand > 0) {
                int out = 1 + random.nextInt(onHand);
                long taken = Valuation.outgoing(onHand, value, out);
                assertThat(taken).isBetween(0L, value);
                onHand -= out;
                value -= taken;
            }
            assertThat(value).isZero();
        }
    }

    @Test
    void withoutStockNothingIsTaken() {
        assertThat(Valuation.outgoing(0, 0, 5)).isZero();
        assertThat(Valuation.outgoing(-2, 0, 1)).isZero();
    }

    @Test
    void incomingUsesTheUnitCostOrTheAverage() {
        assertThat(Valuation.incoming(10, 1000, 4, 125L)).isEqualTo(500);
        assertThat(Valuation.incoming(3, 100, 2, null)).isEqualTo(66); // average 33, rounded down
        assertThat(Valuation.incoming(0, 0, 2, null)).isZero();
        assertThat(Valuation.averageCost(4, 1000)).isEqualTo(250);
    }

    @Test
    void shareSplitsExactly() {
        assertThat(Valuation.share(100, 1, 3)).isEqualTo(33);
        assertThat(Valuation.share(100, 2, 3)).isEqualTo(67);
        assertThat(Valuation.share(100, 3, 3)).isEqualTo(100);
    }
}
