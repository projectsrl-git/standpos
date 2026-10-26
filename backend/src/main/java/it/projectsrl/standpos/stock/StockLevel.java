package it.projectsrl.standpos.stock;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Read-only view of a product's stock level; only {@link StockLedger} writes it, with row locks. */
@Entity
@Immutable
@Table(name = "stock_level")
public class StockLevel extends TenantOwnedEntity {

    @Column(name = "venue_id", nullable = false)
    private UUID venueId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(name = "value_cents", nullable = false)
    private long valueCents;

    protected StockLevel() {}

    public UUID getVenueId() {
        return venueId;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getOnHand() {
        return onHand;
    }

    public long getValueCents() {
        return valueCents;
    }
}
