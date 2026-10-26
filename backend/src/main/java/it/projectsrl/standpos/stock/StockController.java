package it.projectsrl.standpos.stock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Stock of one venue: levels, low-stock list, movements, adjustments and waste, counts, sold-out by hand. */
@RestController
@RequestMapping("/api/v1/venues/{venueId}")
class StockController {

    record AdjustmentRequest(@NotNull UUID productId, @NotNull MovementType type, int quantity,
            @NotBlank @Size(max = 200) String reason) {}

    record SoldOutRequest(boolean soldOut) {}

    record MovementResponse(UUID id, UUID productId, MovementType type, int quantity, long valueCents,
            int onHandAfter, String referenceType, UUID referenceId, UUID userId, String note, Instant createdAt) {

        static MovementResponse of(StockMovement m) {
            return new MovementResponse(m.getId(), m.getProductId(), m.getType(), m.getQuantity(), m.getValueCents(),
                    m.getOnHandAfter(), m.getReferenceType(), m.getReferenceId(), m.getUserId(), m.getNote(),
                    m.getCreatedAt());
        }
    }

    record CountRequest(@Size(max = 200) String notes) {}

    record CountedRequest(@NotNull List<@Valid CountedLine> lines) {}

    record CountedLine(@NotNull UUID productId, Integer counted) {}

    record CountLineResponse(UUID productId, int expected, Integer counted, Integer difference) {}

    record CountResponse(UUID id, StockCount.Status status, String notes, Instant createdAt, Instant postedAt,
            List<CountLineResponse> lines) {

        static CountResponse of(StockService.CountView v) {
            return new CountResponse(v.count().getId(), v.count().getStatus(), v.count().getNotes(),
                    v.count().getCreatedAt(), v.count().getPostedAt(), v.lines().stream()
                            .map(l -> new CountLineResponse(l.getProductId(), l.getExpected(), l.getCounted(),
                                    l.getDifference()))
                            .toList());
        }

        static CountResponse summary(StockCount c) {
            return new CountResponse(c.getId(), c.getStatus(), c.getNotes(), c.getCreatedAt(), c.getPostedAt(),
                    List.of());
        }
    }

    private final StockService stock;

    StockController(StockService stock) {
        this.stock = stock;
    }

    @GetMapping("/stock")
    @PreAuthorize("@authz.canManageStock(#venueId)")
    List<StockService.LevelView> levels(@PathVariable UUID venueId) {
        return stock.levels(venueId);
    }

    @GetMapping("/stock/low")
    @PreAuthorize("@authz.canManageStock(#venueId)")
    List<StockService.LevelView> low(@PathVariable UUID venueId) {
        return stock.lowStock(venueId);
    }

    @GetMapping("/stock/movements")
    @PreAuthorize("@authz.canManageStock(#venueId)")
    List<MovementResponse> movements(@PathVariable UUID venueId, @RequestParam(required = false) UUID productId,
            @RequestParam(defaultValue = "100") int limit) {
        return stock.movements(venueId, productId, limit).stream().map(MovementResponse::of).toList();
    }

    @PostMapping("/stock/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.canManageStock(#venueId)")
    StockService.LevelView adjust(@PathVariable UUID venueId, @Valid @RequestBody AdjustmentRequest r) {
        return stock.adjust(venueId, r.productId(), r.type(), r.quantity(), r.reason());
    }

    /** "Finite le lasagne": the manager's manual sold-out flag, pushed to every register at once. */
    @PutMapping("/products/{productId}/sold-out")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    StockService.LevelView soldOut(@PathVariable UUID venueId, @PathVariable UUID productId,
            @RequestBody SoldOutRequest r) {
        return stock.setSoldOut(venueId, productId, r.soldOut());
    }

    @GetMapping("/stock-counts")
    @PreAuthorize("@authz.canManageStock(#venueId)")
    List<CountResponse> counts(@PathVariable UUID venueId) {
        return stock.counts(venueId).stream().map(CountResponse::summary).toList();
    }

    @PostMapping("/stock-counts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.canManageStock(#venueId)")
    CountResponse openCount(@PathVariable UUID venueId, @Valid @RequestBody CountRequest r) {
        return CountResponse.of(stock.openCount(venueId, r.notes()));
    }

    @GetMapping("/stock-counts/{countId}")
    @PreAuthorize("@authz.canManageStock(#venueId)")
    CountResponse count(@PathVariable UUID venueId, @PathVariable UUID countId) {
        return CountResponse.of(stock.count(venueId, countId));
    }

    @PutMapping("/stock-counts/{countId}")
    @PreAuthorize("@authz.canManageStock(#venueId)")
    CountResponse enterCounts(@PathVariable UUID venueId, @PathVariable UUID countId,
            @Valid @RequestBody CountedRequest r) {
        return CountResponse.of(stock.enterCounts(venueId, countId, r.lines().stream()
                .map(l -> new StockService.CountedQuantity(l.productId(), l.counted()))
                .toList()));
    }

    @PostMapping("/stock-counts/{countId}/post")
    @PreAuthorize("@authz.canManageStock(#venueId)")
    CountResponse postCount(@PathVariable UUID venueId, @PathVariable UUID countId) {
        return CountResponse.of(stock.postCount(venueId, countId));
    }

    @DeleteMapping("/stock-counts/{countId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.canManageStock(#venueId)")
    void deleteCount(@PathVariable UUID venueId, @PathVariable UUID countId) {
        stock.deleteCount(venueId, countId);
    }
}
