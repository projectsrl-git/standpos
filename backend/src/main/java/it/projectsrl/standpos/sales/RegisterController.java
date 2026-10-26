package it.projectsrl.standpos.sales;

import it.projectsrl.standpos.auth.PinLoginService;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.printing.AgentPresence;
import it.projectsrl.standpos.printing.PrintService;
import it.projectsrl.standpos.usage.UsageService;
import it.projectsrl.standpos.venue.VenueRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The register: sale, orders of the day, reprint, void with a manager PIN, shifts and the status banners. */
@RestController
@RequestMapping("/api/v1")
class RegisterController {

    record OrderRequest(@NotNull UUID clientOrderId, @NotNull @Size(min = 1, max = OrderService.MAX_LINES)
            List<@Valid LineRequest> lines, @NotNull Payment.Method paymentMethod, @PositiveOrZero Long tenderedCents,
            boolean printReceipt) {}

    record LineRequest(@NotNull UUID productId, int quantity, @Size(max = 120) String note) {}

    record LineResponse(UUID id, int lineNo, UUID productId, String name, long unitPriceCents, int quantity,
            long lineTotalCents, String note, OrderLine.VoidDisposition voidDisposition) {}

    record PaymentResponse(Payment.Method method, Payment.Status status, long amountCents, Long tenderedCents,
            Long changeCents, boolean needsReconciliation) {}

    record OrderResponse(UUID id, UUID clientOrderId, int dailyNumber, LocalDate businessDate,
            CustomerOrder.Status status, long totalCents, Instant createdAt, Instant paidAt, UUID deviceId,
            boolean receiptPrinted, OrderService.ReceiptState receipt, String receiptError, List<LineResponse> lines,
            PaymentResponse payment, Instant voidedAt, String voidReason) {

        static OrderResponse of(OrderService.OrderView v) {
            CustomerOrder o = v.order();
            Payment p = v.payment();
            return new OrderResponse(o.getId(), o.getClientOrderId(), o.getDailyNumber(), o.getBusinessDate(),
                    o.getStatus(), o.getTotalCents(), o.getCreatedAt(), o.getPaidAt(), o.getDeviceId(),
                    o.isReceiptPrinted(), v.receipt(), v.receiptError(),
                    v.lines().stream().map(l -> new LineResponse(l.getId(), l.getLineNo(), l.getProductId(),
                            l.getProductName(), l.getUnitPriceCents(), l.getQuantity(), l.getLineTotalCents(),
                            l.getNote(), l.getVoidDisposition())).toList(),
                    p == null ? null : new PaymentResponse(p.getMethod(), p.getStatus(), p.getAmountCents(),
                            p.getTenderedCents(), p.getChangeCents(), p.isNeedsReconciliation()),
                    o.getVoidedAt(), o.getVoidReason());
        }
    }

    record VoidRequest(@NotNull UUID managerId, @NotBlank @Pattern(regexp = "\\d{4,6}") String pin,
            @NotBlank @Size(max = 200) String reason, @NotNull List<@Valid VoidLineRequest> lines) {}

    record VoidLineRequest(@NotNull UUID lineId, @NotNull OrderLine.VoidDisposition disposition) {}

    record OpenShiftRequest(@PositiveOrZero long openingFloatCents) {}

    record CloseShiftRequest(@PositiveOrZero long countedCashCents) {}

    record ShiftResponse(UUID id, UUID cashierId, Instant openedAt, long openingFloatCents, Instant closedAt,
            int paidOrders, int voidedOrders, long cashSalesCents, long manualCardCents, long expectedCashCents,
            Long countedCashCents, Long differenceCents) {

        static ShiftResponse of(ShiftService.Summary s) {
            Shift shift = s.shift();
            return new ShiftResponse(shift.getId(), shift.getCashierId(), shift.getOpenedAt(),
                    shift.getOpeningFloatCents(), shift.getClosedAt(), s.paidOrders(), s.voidedOrders(),
                    s.cashSalesCents(), s.manualCardCents(), s.expectedCashCents(), s.countedCashCents(),
                    s.differenceCents());
        }
    }

    record PrinterStatus(UUID id, String name, UUID agentId, boolean online) {}

    record UsageBanner(boolean capReached, Instant blockedFrom, boolean blocked) {}

    record StatusResponse(ShiftResponse shift, PrinterStatus printer, UsageBanner usage) {}

    private final OrderService orders;
    private final ShiftService shifts;
    private final PinLoginService pins;
    private final PrintService prints;
    private final AgentPresence presence;
    private final UsageService usage;
    private final VenueRepository venues;
    private final Clock clock;

    RegisterController(OrderService orders, ShiftService shifts, PinLoginService pins, PrintService prints,
            AgentPresence presence, UsageService usage, VenueRepository venues, Clock clock) {
        this.orders = orders;
        this.shifts = shifts;
        this.pins = pins;
        this.prints = prints;
        this.presence = presence;
        this.usage = usage;
        this.venues = venues;
        this.clock = clock;
    }

    /** 201 for a new order, 200 when the client UUID matches an order already created (a retry). */
    @PostMapping("/orders")
    @PreAuthorize("@authz.isCashier()")
    ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequest r) {
        OrderService.Result result = orders.create(RegisterSession.current(), new OrderService.NewOrder(
                r.clientOrderId(),
                r.lines().stream().map(l -> new OrderService.NewLine(l.productId(), l.quantity(), l.note())).toList(),
                r.paymentMethod(), r.tenderedCents(), r.printReceipt()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(OrderResponse.of(result.view()));
    }

    @GetMapping("/register/orders")
    @PreAuthorize("@authz.isCashier()")
    List<OrderResponse> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {
        return orders.orders(RegisterSession.current(), date).stream().map(OrderResponse::of).toList();
    }

    @PostMapping("/register/orders/{orderId}/receipt")
    @PreAuthorize("@authz.isCashier()")
    OrderResponse printReceipt(@PathVariable UUID orderId) {
        return OrderResponse.of(orders.printReceipt(RegisterSession.current(), orderId));
    }

    @PostMapping("/register/orders/{orderId}/void")
    @PreAuthorize("@authz.isCashier()")
    OrderResponse voidOrder(@PathVariable UUID orderId, @Valid @RequestBody VoidRequest r) {
        return OrderResponse.of(orders.voidOrder(RegisterSession.current(), orderId, r.managerId(), r.pin(),
                r.reason(), r.lines().stream()
                        .map(l -> new OrderService.VoidLine(l.lineId(), l.disposition()))
                        .toList()));
    }

    /** Managers and owners who can approve a void here with their PIN. */
    @GetMapping("/register/approvers")
    @PreAuthorize("@authz.isCashier()")
    List<PinLoginService.Cashier> approvers() {
        return pins.approvers(RegisterSession.current().device());
    }

    @GetMapping("/register/shift")
    @PreAuthorize("@authz.isCashier()")
    ResponseEntity<ShiftResponse> shift() {
        return shifts.current(RegisterSession.current()).map(s -> ResponseEntity.ok(ShiftResponse.of(s)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/register/shift/open")
    @PreAuthorize("@authz.isCashier()")
    ResponseEntity<ShiftResponse> openShift(@Valid @RequestBody OpenShiftRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ShiftResponse.of(shifts.open(RegisterSession.current(), r.openingFloatCents())));
    }

    @PostMapping("/register/shift/close")
    @PreAuthorize("@authz.isCashier()")
    ShiftResponse closeShift(@Valid @RequestBody CloseShiftRequest r) {
        return ShiftResponse.of(shifts.close(RegisterSession.current(), r.countedCashCents()));
    }

    /** What the register's banners need: the open shift, its printer's state and the usage cap. */
    @GetMapping("/register/status")
    @PreAuthorize("@authz.isCashier()")
    @Transactional(readOnly = true)
    public StatusResponse status() {
        RegisterSession session = RegisterSession.current();
        var venue = venues.findById(session.venueId()).orElseThrow(() -> ApiException.notFound("Venue"));
        ShiftResponse shift = shifts.current(session).map(ShiftResponse::of).orElse(null);
        PrinterStatus printer = prints.printerOf(session.deviceId())
                .map(p -> new PrinterStatus(p.getId(), p.getName(), p.getAgentId(), presence.isOnline(p.getAgentId())))
                .orElse(null);
        UsageService.Status u = usage.status(venue, clock.instant());
        return new StatusResponse(shift, printer, new UsageBanner(u.capReached(), u.blockedFrom(), u.blocked()));
    }
}
