package it.projectsrl.standpos.stock;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One product in a count. {@code expected} is the quantity when the session opened; {@code difference} is what
 * posting found against the quantity at that moment (sales may happen in between).
 */
@Entity
@Table(name = "stock_count_line")
public class StockCountLine extends TenantOwnedEntity {

    @Column(name = "stock_count_id", nullable = false, updatable = false)
    private UUID stockCountId;

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private int expected;

    private Integer counted;

    private Integer difference;

    protected StockCountLine() {}

    public StockCountLine(UUID stockCountId, UUID venueId, UUID productId, int expected) {
        this.stockCountId = stockCountId;
        this.venueId = venueId;
        this.productId = productId;
        this.expected = expected;
    }

    public void count(Integer counted) {
        this.counted = counted;
    }

    public void posted(Integer difference) {
        this.difference = difference;
    }

    public UUID getStockCountId() {
        return stockCountId;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getExpected() {
        return expected;
    }

    public Integer getCounted() {
        return counted;
    }

    public Integer getDifference() {
        return difference;
    }
}
