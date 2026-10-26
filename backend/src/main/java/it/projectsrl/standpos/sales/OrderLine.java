package it.projectsrl.standpos.sales;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/** One line of an order. Name, unit price and route are copied from the product at sale time. */
@Entity
@Table(name = "order_line")
public class OrderLine extends TenantOwnedEntity {

    /** Where a voided line's goods go. */
    public enum VoidDisposition {
        RETURN_TO_STOCK,
        WASTE
    }

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, updatable = false)
    private String productName;

    @Column(name = "unit_price_cents", nullable = false, updatable = false)
    private long unitPriceCents;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "line_total_cents", nullable = false, updatable = false)
    private long lineTotalCents;

    @Column(name = "route_id", updatable = false)
    private UUID routeId;

    @Column(updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "void_disposition")
    private VoidDisposition voidDisposition;

    protected OrderLine() {}

    public OrderLine(UUID orderId, UUID venueId, int lineNo, UUID productId, String productName, long unitPriceCents,
            int quantity, UUID routeId, String note) {
        this.orderId = orderId;
        this.venueId = venueId;
        this.lineNo = lineNo;
        this.productId = productId;
        this.productName = productName;
        this.unitPriceCents = unitPriceCents;
        this.quantity = quantity;
        this.lineTotalCents = Math.multiplyExact(unitPriceCents, quantity);
        this.routeId = routeId;
        this.note = note;
    }

    public void voidTo(VoidDisposition disposition) {
        this.voidDisposition = disposition;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public int getLineNo() {
        return lineNo;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public long getUnitPriceCents() {
        return unitPriceCents;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getLineTotalCents() {
        return lineTotalCents;
    }

    public UUID getRouteId() {
        return routeId;
    }

    public String getNote() {
        return note;
    }

    public VoidDisposition getVoidDisposition() {
        return voidDisposition;
    }
}
