package it.projectsrl.standpos.stock;

import it.projectsrl.standpos.audit.AuditService;
import it.projectsrl.standpos.catalog.Product;
import it.projectsrl.standpos.catalog.ProductRepository;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.venue.Venue;
import it.projectsrl.standpos.venue.VenueRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Suppliers and goods receipts. Posting a receipt writes RECEIPT movements and makes it immutable. */
@Service
public class GoodsReceiptService {

    public record SupplierData(String name, String contactName, String phone, String email, String notes,
            boolean active) {}

    public record ReceiptData(UUID supplierId, String documentRef, LocalDate receivedOn, String notes,
            List<LineData> lines) {}

    public record LineData(UUID productId, int quantity, Long unitCostCents) {}

    public record ReceiptView(GoodsReceipt receipt, List<GoodsReceiptLine> lines) {

        public long totalCostCents() {
            return lines.stream()
                    .filter(l -> l.getUnitCostCents() != null)
                    .mapToLong(l -> l.getUnitCostCents() * l.getQuantity())
                    .sum();
        }
    }

    private final VenueRepository venues;
    private final ProductRepository products;
    private final SupplierRepository suppliers;
    private final GoodsReceiptRepository receipts;
    private final GoodsReceiptLineRepository lines;
    private final StockLedger ledger;
    private final AuditService audit;
    private final Clock clock;

    public GoodsReceiptService(VenueRepository venues, ProductRepository products, SupplierRepository suppliers,
            GoodsReceiptRepository receipts, GoodsReceiptLineRepository lines, StockLedger ledger, AuditService audit,
            Clock clock) {
        this.venues = venues;
        this.products = products;
        this.suppliers = suppliers;
        this.receipts = receipts;
        this.lines = lines;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    // Suppliers

    @Transactional(readOnly = true)
    public List<Supplier> suppliers() {
        return suppliers.findAllByOrderByName();
    }

    @Transactional
    public Supplier createSupplier(SupplierData data) {
        Supplier supplier = new Supplier(data.name());
        apply(supplier, data);
        return suppliers.saveAndFlush(supplier);
    }

    @Transactional
    public Supplier updateSupplier(UUID supplierId, SupplierData data) {
        Supplier supplier = supplier(supplierId);
        apply(supplier, data);
        return suppliers.saveAndFlush(supplier);
    }

    /** Deletes a supplier no receipt mentions; otherwise it can be deactivated. */
    @Transactional
    public void deleteSupplier(UUID supplierId) {
        Supplier supplier = supplier(supplierId);
        if (receipts.existsBySupplierId(supplierId)) {
            throw ApiException.conflict("stock.supplier_in_use", "Goods receipts reference this supplier");
        }
        suppliers.delete(supplier);
    }

    // Goods receipts

    @Transactional(readOnly = true)
    public List<ReceiptView> receipts(UUID venueId) {
        venue(venueId);
        return receipts.findAllByVenueIdOrderByReceivedOnDescCreatedAtDesc(venueId).stream()
                .map(r -> new ReceiptView(r, lines.findAllByGoodsReceiptIdOrderByLineNo(r.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReceiptView receipt(UUID venueId, UUID receiptId) {
        GoodsReceipt receipt = receiptEntity(venueId, receiptId);
        return new ReceiptView(receipt, lines.findAllByGoodsReceiptIdOrderByLineNo(receiptId));
    }

    @Transactional
    public ReceiptView createReceipt(UUID venueId, ReceiptData data) {
        venue(venueId);
        GoodsReceipt receipt = new GoodsReceipt(venueId, StockService.currentUserId());
        receipt.update(checkSupplier(data.supplierId()), StockService.blankToNull(data.documentRef()),
                data.receivedOn(), StockService.blankToNull(data.notes()));
        receipts.saveAndFlush(receipt);
        return new ReceiptView(receipt, replaceLines(receipt, data.lines()));
    }

    @Transactional
    public ReceiptView updateReceipt(UUID venueId, UUID receiptId, ReceiptData data) {
        GoodsReceipt receipt = receiptEntity(venueId, receiptId);
        receipt.update(checkSupplier(data.supplierId()), StockService.blankToNull(data.documentRef()),
                data.receivedOn(), StockService.blankToNull(data.notes()));
        lines.deleteAllOfReceipt(receiptId);
        return new ReceiptView(receipt, replaceLines(receipt, data.lines()));
    }

    /** Writes one RECEIPT movement per line (at its unit cost, or the average cost) and freezes the receipt. */
    @Transactional
    public ReceiptView postReceipt(UUID venueId, UUID receiptId) {
        Venue venue = venue(venueId);
        GoodsReceipt receipt = receiptEntity(venueId, receiptId);
        receipt.requireDraft();
        List<GoodsReceiptLine> receiptLines = lines.findAllByGoodsReceiptIdOrderByLineNo(receiptId);
        if (receiptLines.isEmpty()) {
            throw ApiException.badRequest("stock.receipt_empty", "A goods receipt needs at least one line");
        }
        StockLedger.Locked locked =
                ledger.lock(venue, receiptLines.stream().map(GoodsReceiptLine::getProductId).toList());
        StockLedger.Reference reference = StockLedger.Reference.goodsReceipt(receiptId);
        long value = 0;
        for (GoodsReceiptLine line : receiptLines) {
            value += ledger.receive(locked, line.getProductId(), MovementType.RECEIPT, line.getQuantity(),
                    line.getUnitCostCents(), reference, receipt.getDocumentRef());
        }
        receipt.post(clock.instant(), StockService.currentUserId());
        receipts.saveAndFlush(receipt);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("lines", receiptLines.size());
        after.put("units", receiptLines.stream().mapToInt(GoodsReceiptLine::getQuantity).sum());
        after.put("valueCents", value);
        after.put("documentRef", receipt.getDocumentRef());
        audit.record("goods_receipt.posted", "goods_receipt", receiptId, null, after);
        return new ReceiptView(receipt, receiptLines);
    }

    @Transactional
    public void deleteReceipt(UUID venueId, UUID receiptId) {
        GoodsReceipt receipt = receiptEntity(venueId, receiptId);
        receipt.requireDraft();
        lines.deleteAllOfReceipt(receiptId);
        receipts.delete(receipt);
    }

    private List<GoodsReceiptLine> replaceLines(GoodsReceipt receipt, List<LineData> data) {
        List<GoodsReceiptLine> result = new ArrayList<>();
        int lineNo = 1;
        for (LineData line : data == null ? List.<LineData>of() : data) {
            Product product = products.findByIdAndVenueId(line.productId(), receipt.getVenueId())
                    .orElseThrow(() -> ApiException.notFound("Product"));
            if (!product.isTrackStock()) {
                throw ApiException.badRequest("stock.product_not_tracked", "Products without stock tracking")
                        .with("productIds", List.of(product.getId()));
            }
            if (line.quantity() <= 0) {
                throw ApiException.badRequest("stock.invalid_quantity", "Quantities must be positive");
            }
            result.add(new GoodsReceiptLine(receipt.getId(), receipt.getVenueId(), product.getId(), lineNo++,
                    line.quantity(), line.unitCostCents()));
        }
        return lines.saveAllAndFlush(result);
    }

    private UUID checkSupplier(UUID supplierId) {
        if (supplierId != null) {
            supplier(supplierId);
        }
        return supplierId;
    }

    private static void apply(Supplier supplier, SupplierData d) {
        supplier.update(d.name().trim(), StockService.blankToNull(d.contactName()),
                StockService.blankToNull(d.phone()), StockService.blankToNull(d.email()),
                StockService.blankToNull(d.notes()), d.active());
    }

    private Venue venue(UUID venueId) {
        return venues.findById(venueId).orElseThrow(() -> ApiException.notFound("Venue"));
    }

    private Supplier supplier(UUID supplierId) {
        return suppliers.findById(supplierId).orElseThrow(() -> ApiException.notFound("Supplier"));
    }

    private GoodsReceipt receiptEntity(UUID venueId, UUID receiptId) {
        return receipts.findByIdAndVenueId(receiptId, venueId).orElseThrow(() -> ApiException.notFound("Goods receipt"));
    }
}
