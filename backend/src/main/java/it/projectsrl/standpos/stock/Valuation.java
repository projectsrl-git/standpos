package it.projectsrl.standpos.stock;

/**
 * Stock valuation in integer cents, without floating point (docs/adr/0007-stock-ledger-and-valuation.md).
 *
 * <p>Each level keeps its quantity and its total value. The weighted average cost is derived as
 * {@code value / onHand}, rounded down. Units leaving the stock take a proportional share of the value, rounded half
 * up, and the last units take whatever value is left, so the value is exactly zero when the stock is.
 */
public final class Valuation {

    private Valuation() {}

    /** Value removed when {@code quantity} units leave a stock of {@code onHand} units worth {@code value} cents. */
    public static long outgoing(int onHand, long value, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (onHand <= 0 || value == 0) {
            return 0;
        }
        if (quantity >= onHand) {
            return value;
        }
        // round(value * quantity / onHand), half up, in integers.
        long numerator = Math.multiplyExact(value, quantity);
        return (2 * numerator + onHand) / (2L * onHand);
    }

    /**
     * Value added when {@code quantity} units come in: at their unit cost when known (a goods receipt line with a
     * cost), otherwise at the current average cost (zero when there is no stock to average).
     */
    public static long incoming(int onHand, long value, int quantity, Long unitCostCents) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        long unit = unitCostCents != null ? unitCostCents : averageCost(onHand, value);
        return Math.multiplyExact(unit, quantity);
    }

    /** Weighted average cost per unit, rounded down; zero without stock. */
    public static long averageCost(int onHand, long value) {
        return onHand > 0 ? value / onHand : 0;
    }

    /** Splits {@code total} in proportion {@code part / whole}, half up: the share of a sale's value one line gets back. */
    public static long share(long total, int part, int whole) {
        if (part == whole) {
            return total;
        }
        long numerator = Math.multiplyExact(total, part);
        return (2 * numerator + whole) / (2L * whole);
    }
}
