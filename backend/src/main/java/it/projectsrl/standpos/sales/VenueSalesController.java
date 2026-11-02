package it.projectsrl.standpos.sales;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only sales of one venue for its owners and managers: the day's orders and shifts, an order's receipt. */
@RestController
@RequestMapping("/api/v1/venues/{venueId}")
class VenueSalesController {

    private final DayOverviewService overview;
    private final ReceiptPreviews previews;

    VenueSalesController(DayOverviewService overview, ReceiptPreviews previews) {
        this.overview = overview;
        this.previews = previews;
    }

    /** Orders, shifts and cashier totals of a business day; today in the venue's time zone when no date is given. */
    @GetMapping("/day-overview")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    DayOverviewService.Overview overview(@PathVariable UUID venueId, @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return overview.overview(venueId, date);
    }

    @GetMapping("/orders/{orderId}/receipt-preview")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    ReceiptPreviews.Preview receiptPreview(@PathVariable UUID venueId, @PathVariable UUID orderId) {
        return previews.of(venueId, orderId);
    }
}
