package it.projectsrl.standpos.stock;

/**
 * Whether a product can be sold and whether it is running low, as the register shows it.
 *
 * <p>Sold out is the manager's manual flag ("finite le lasagne") or, for a tracked product, no stock left while the
 * venue does not allow negative stock. The automatic part is derived from the stock level, so it turns back on by
 * itself when goods come in; the manual flag stays until a manager clears it.
 */
public record Availability(boolean soldOut, boolean low, Integer onHand) {

    public static Availability of(boolean trackStock, Integer onHand, boolean manualSoldOut, Integer lowThreshold,
            boolean allowNegativeStock) {
        if (!trackStock) {
            return new Availability(manualSoldOut, false, null);
        }
        int quantity = onHand == null ? 0 : onHand;
        boolean empty = quantity <= 0 && !allowNegativeStock;
        boolean low = lowThreshold != null && quantity <= lowThreshold;
        return new Availability(manualSoldOut || empty, low, quantity);
    }
}
