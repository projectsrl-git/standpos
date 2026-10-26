package it.projectsrl.standpos.stock;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One product of a goods receipt; the unit cost, when known, feeds the weighted average cost. */
@Entity
@Table(name = "goods_receipt_line")
public class GoodsReceiptLine extends TenantOwnedEntity {

    @Column(name = "goods_receipt_id", nullable = false, updatable = false)
    private UUID goodsReceiptId;

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_cost_cents", updatable = false)
    private Long unitCostCents;

    protected GoodsReceiptLine() {}

    public GoodsReceiptLine(UUID goodsReceiptId, UUID venueId, UUID productId, int lineNo, int quantity,
            Long unitCostCents) {
        this.goodsReceiptId = goodsReceiptId;
        this.venueId = venueId;
        this.productId = productId;
        this.lineNo = lineNo;
        this.quantity = quantity;
        this.unitCostCents = unitCostCents;
    }

    public UUID getGoodsReceiptId() {
        return goodsReceiptId;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getLineNo() {
        return lineNo;
    }

    public int getQuantity() {
        return quantity;
    }

    public Long getUnitCostCents() {
        return unitCostCents;
    }
}
