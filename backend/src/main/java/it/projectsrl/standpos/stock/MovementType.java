package it.projectsrl.standpos.stock;

/**
 * Stock movement types (docs/adr/0007-stock-ledger-and-valuation.md).
 *
 * <ul>
 *   <li>RECEIPT: goods received, from a posted goods receipt.
 *   <li>SALE: goods sold, written in the sale transaction.
 *   <li>SALE_REVERSAL: a sale undone without the goods going back on the shelf by themselves: a failed card payment
 *       (Phase 2), or the first half of a void to waste.
 *   <li>RETURN_TO_STOCK: a voided line whose goods go back on the shelf.
 *   <li>WASTE: goods thrown away, from a void or entered by hand with a reason.
 *   <li>ADJUSTMENT: breakage, gifts, corrections, with a reason; either sign.
 *   <li>COUNT: the difference found by a stock count; either sign.
 * </ul>
 */
public enum MovementType {
    RECEIPT,
    SALE,
    SALE_REVERSAL,
    RETURN_TO_STOCK,
    WASTE,
    ADJUSTMENT,
    COUNT
}
