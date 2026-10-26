package it.projectsrl.standpos.stock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Suppliers (tenant-wide) and goods receipts (per venue). */
@RestController
@RequestMapping("/api/v1")
class GoodsReceiptController {

    record SupplierRequest(@NotBlank @Size(max = 120) String name, @Size(max = 120) String contactName,
            @Size(max = 40) String phone, @Email @Size(max = 254) String email, @Size(max = 500) String notes,
            Boolean active) {}

    record SupplierResponse(UUID id, String name, String contactName, String phone, String email, String notes,
            boolean active) {

        static SupplierResponse of(Supplier s) {
            return new SupplierResponse(s.getId(), s.getName(), s.getContactName(), s.getPhone(), s.getEmail(),
                    s.getNotes(), s.isActive());
        }
    }

    record ReceiptRequest(UUID supplierId, @Size(max = 60) String documentRef, @NotNull LocalDate receivedOn,
            @Size(max = 500) String notes, @NotNull @Size(max = 200) List<@Valid LineRequest> lines) {}

    record LineRequest(@NotNull UUID productId, @Positive int quantity, @PositiveOrZero Long unitCostCents) {}

    record LineResponse(UUID productId, int quantity, Long unitCostCents) {}

    record ReceiptResponse(UUID id, UUID supplierId, String documentRef, LocalDate receivedOn, String notes,
            GoodsReceipt.Status status, Instant postedAt, long totalCostCents, List<LineResponse> lines) {

        static ReceiptResponse of(GoodsReceiptService.ReceiptView v) {
            GoodsReceipt r = v.receipt();
            return new ReceiptResponse(r.getId(), r.getSupplierId(), r.getDocumentRef(), r.getReceivedOn(),
                    r.getNotes(), r.getStatus(), r.getPostedAt(), v.totalCostCents(), v.lines().stream()
                            .map(l -> new LineResponse(l.getProductId(), l.getQuantity(), l.getUnitCostCents()))
                            .toList());
        }
    }

    private final GoodsReceiptService service;

    GoodsReceiptController(GoodsReceiptService service) {
        this.service = service;
    }

    @GetMapping("/suppliers")
    @PreAuthorize("@authz.canManageSuppliers()")
    List<SupplierResponse> suppliers() {
        return service.suppliers().stream().map(SupplierResponse::of).toList();
    }

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.canManageSuppliers()")
    SupplierResponse createSupplier(@Valid @RequestBody SupplierRequest r) {
        return SupplierResponse.of(service.createSupplier(supplier(r)));
    }

    @PutMapping("/suppliers/{supplierId}")
    @PreAuthorize("@authz.canManageSuppliers()")
    SupplierResponse updateSupplier(@PathVariable UUID supplierId, @Valid @RequestBody SupplierRequest r) {
        return SupplierResponse.of(service.updateSupplier(supplierId, supplier(r)));
    }

    @DeleteMapping("/suppliers/{supplierId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.canManageSuppliers()")
    void deleteSupplier(@PathVariable UUID supplierId) {
        service.deleteSupplier(supplierId);
    }

    @GetMapping("/venues/{venueId}/goods-receipts")
    @PreAuthorize("@authz.canManageStock(#venueId)")
    List<ReceiptResponse> receipts(@PathVariable UUID venueId) {
        return service.receipts(venueId).stream().map(ReceiptResponse::of).toList();
    }

    @PostMapping("/venues/{venueId}/goods-receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.canManageStock(#venueId)")
    ReceiptResponse createReceipt(@PathVariable UUID venueId, @Valid @RequestBody ReceiptRequest r) {
        return ReceiptResponse.of(service.createReceipt(venueId, receipt(r)));
    }

    @GetMapping("/venues/{venueId}/goods-receipts/{receiptId}")
    @PreAuthorize("@authz.canManageStock(#venueId)")
    ReceiptResponse receipt(@PathVariable UUID venueId, @PathVariable UUID receiptId) {
        return ReceiptResponse.of(service.receipt(venueId, receiptId));
    }

    @PutMapping("/venues/{venueId}/goods-receipts/{receiptId}")
    @PreAuthorize("@authz.canManageStock(#venueId)")
    ReceiptResponse updateReceipt(@PathVariable UUID venueId, @PathVariable UUID receiptId,
            @Valid @RequestBody ReceiptRequest r) {
        return ReceiptResponse.of(service.updateReceipt(venueId, receiptId, receipt(r)));
    }

    @PostMapping("/venues/{venueId}/goods-receipts/{receiptId}/post")
    @PreAuthorize("@authz.canManageStock(#venueId)")
    ReceiptResponse postReceipt(@PathVariable UUID venueId, @PathVariable UUID receiptId) {
        return ReceiptResponse.of(service.postReceipt(venueId, receiptId));
    }

    @DeleteMapping("/venues/{venueId}/goods-receipts/{receiptId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.canManageStock(#venueId)")
    void deleteReceipt(@PathVariable UUID venueId, @PathVariable UUID receiptId) {
        service.deleteReceipt(venueId, receiptId);
    }

    private static GoodsReceiptService.SupplierData supplier(SupplierRequest r) {
        return new GoodsReceiptService.SupplierData(r.name(), r.contactName(), r.phone(), r.email(), r.notes(),
                r.active() == null || r.active());
    }

    private static GoodsReceiptService.ReceiptData receipt(ReceiptRequest r) {
        return new GoodsReceiptService.ReceiptData(r.supplierId(), r.documentRef(), r.receivedOn(), r.notes(),
                r.lines().stream()
                        .map(l -> new GoodsReceiptService.LineData(l.productId(), l.quantity(), l.unitCostCents()))
                        .toList());
    }
}
