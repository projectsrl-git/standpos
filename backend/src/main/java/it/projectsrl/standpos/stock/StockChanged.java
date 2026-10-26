package it.projectsrl.standpos.stock;

import java.util.UUID;

/**
 * Published inside the transaction that changed a product's stock or availability; pushed to the venue's registers
 * after commit (topic {@code /topic/venues/{venueId}/stock}). {@code onHand} is null for untracked products.
 */
public record StockChanged(UUID venueId, UUID productId, Integer onHand, boolean soldOut, boolean low) {

    public static StockChanged of(UUID venueId, UUID productId, Availability availability) {
        return new StockChanged(venueId, productId, availability.onHand(), availability.soldOut(), availability.low());
    }
}
