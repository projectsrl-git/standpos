package it.projectsrl.standpos.sales;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * How an order was paid. Phase 1: cash (amount tendered and change) and manual card, which is recorded but not
 * verified and therefore flagged for reconciliation. SumUp card payments arrive in Phase 2.
 */
@Entity
@Table(name = "payment")
public class Payment extends TenantOwnedEntity {

    public enum Method {
        CASH,
        SUMUP_CARD,
        MANUAL_CARD
    }

    public enum Status {
        PENDING,
        CONFIRMED,
        FAILED,
        REFUNDED
    }

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Method method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;

    @Column(name = "tendered_cents", updatable = false)
    private Long tenderedCents;

    @Column(name = "change_cents", updatable = false)
    private Long changeCents;

    @Column(name = "needs_reconciliation", nullable = false)
    private boolean needsReconciliation;

    protected Payment() {}

    private Payment(UUID orderId, Method method, long amountCents, Long tenderedCents, Long changeCents,
            boolean needsReconciliation) {
        this.orderId = orderId;
        this.method = method;
        this.status = Status.CONFIRMED;
        this.amountCents = amountCents;
        this.tenderedCents = tenderedCents;
        this.changeCents = changeCents;
        this.needsReconciliation = needsReconciliation;
    }

    /** Cash: the change is the amount tendered minus the total; with no amount tendered, exact cash. */
    public static Payment cash(UUID orderId, long amountCents, Long tenderedCents) {
        long tendered = tenderedCents == null ? amountCents : tenderedCents;
        return new Payment(orderId, Method.CASH, amountCents, tendered, tendered - amountCents, false);
    }

    /** Card on a terminal StandPOS does not drive: recorded, not verified, flagged for reconciliation. */
    public static Payment manualCard(UUID orderId, long amountCents) {
        return new Payment(orderId, Method.MANUAL_CARD, amountCents, null, null, true);
    }

    public void refund() {
        status = Status.REFUNDED;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Method getMethod() {
        return method;
    }

    public Status getStatus() {
        return status;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Long getTenderedCents() {
        return tenderedCents;
    }

    public Long getChangeCents() {
        return changeCents;
    }

    public boolean isNeedsReconciliation() {
        return needsReconciliation;
    }
}
