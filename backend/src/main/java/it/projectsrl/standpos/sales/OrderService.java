package it.projectsrl.standpos.sales;

import it.projectsrl.standpos.audit.AuditService;
import it.projectsrl.standpos.auth.PinLoginService;
import it.projectsrl.standpos.catalog.Category;
import it.projectsrl.standpos.catalog.CategoryRepository;
import it.projectsrl.standpos.catalog.Product;
import it.projectsrl.standpos.catalog.ProductRepository;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.printing.PrintEvents;
import it.projectsrl.standpos.printing.PrintJobEntity;
import it.projectsrl.standpos.printing.PrintService;
import it.projectsrl.standpos.printing.ReceiptBuilder;
import it.projectsrl.standpos.stock.MovementType;
import it.projectsrl.standpos.stock.StockLedger;
import it.projectsrl.standpos.stock.StockMovement;
import it.projectsrl.standpos.stock.StockMovementRepository;
import it.projectsrl.standpos.stock.Valuation;
import it.projectsrl.standpos.usage.UsageService;
import it.projectsrl.standpos.venue.Venue;
import it.projectsrl.standpos.venue.VenueRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sales at the register (docs/adr/0008-sale-transaction.md). One transaction writes the usage count, the daily
 * number, the order and its snapshotted lines, the stock movements (after locking the stock rows in product order)
 * and the payment, and queues the receipt. A retry with the same client UUID returns the order already created.
 */
@Service
public class OrderService {

    public static final int MAX_LINES = 100;
    public static final int MAX_QUANTITY = 999;

    public record NewLine(UUID productId, int quantity, String note) {}

    public record NewOrder(UUID clientOrderId, List<NewLine> lines, Payment.Method method, Long tenderedCents,
            boolean printReceipt) {}

    /** Receipt state shown in the order list: not requested, no printer linked, or the last receipt job's status. */
    public enum ReceiptState {
        NOT_REQUESTED,
        NO_PRINTER,
        QUEUED,
        SENT,
        PRINTED,
        FAILED
    }

    public record OrderView(CustomerOrder order, List<OrderLine> lines, Payment payment, ReceiptState receipt,
            String receiptError) {}

    public record Result(OrderView view, boolean created) {}

    public record VoidLine(UUID lineId, OrderLine.VoidDisposition disposition) {}

    private final CustomerOrderRepository orders;
    private final OrderLineRepository orderLines;
    private final PaymentRepository payments;
    private final ShiftRepository shifts;
    private final VenueRepository venues;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final StockLedger ledger;
    private final StockMovementRepository movements;
    private final UsageService usage;
    private final OrderNumbers numbers;
    private final PrintService prints;
    private final PinLoginService pins;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderService(CustomerOrderRepository orders, OrderLineRepository orderLines, PaymentRepository payments,
            ShiftRepository shifts, VenueRepository venues, ProductRepository products,
            CategoryRepository categories, StockLedger ledger, StockMovementRepository movements, UsageService usage,
            OrderNumbers numbers, PrintService prints, PinLoginService pins, AuditService audit, TransactionTemplate tx,
            Clock clock) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.payments = payments;
        this.shifts = shifts;
        this.venues = venues;
        this.products = products;
        this.categories = categories;
        this.ledger = ledger;
        this.movements = movements;
        this.usage = usage;
        this.numbers = numbers;
        this.prints = prints;
        this.pins = pins;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    // Sale

    /** Creates the order, or returns the one already created with the same client UUID. Not transactional itself. */
    public Result create(RegisterSession session, NewOrder request) {
        validate(request);
        Optional<Result> replay = tx.execute(status -> replay(session, request.clientOrderId()));
        if (replay != null && replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> sell(session, request));
        } catch (DataIntegrityViolationException e) {
            // The same client UUID committed concurrently (unique key): answer with that order.
            return tx.execute(status -> replay(session, request.clientOrderId())).orElseThrow(() -> e);
        }
    }

    private Optional<Result> replay(RegisterSession session, UUID clientOrderId) {
        return orders.findByClientOrderId(clientOrderId).map(order -> {
            if (!order.getDeviceId().equals(session.deviceId())) {
                throw ApiException.conflict("sales.client_id_reused", "This order id belongs to another register");
            }
            return new Result(view(order), false);
        });
    }

    private Result sell(RegisterSession session, NewOrder request) {
        Shift shift = openShiftOf(session);
        Venue venue = venues.findById(session.venueId()).orElseThrow(() -> ApiException.notFound("Venue"));

        // Products: of this venue, active, not sold out by hand. Quantities are summed per product.
        Map<UUID, Integer> quantities = new TreeMap<>();
        request.lines().forEach(l -> quantities.merge(l.productId(), l.quantity(), Integer::sum));
        Map<UUID, Product> byId = products.findAllById(quantities.keySet()).stream()
                .filter(p -> p.getVenueId().equals(venue.getId()))
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<UUID> unavailable = quantities.keySet().stream()
                .filter(id -> !byId.containsKey(id) || !byId.get(id).isActive() || byId.get(id).isSoldOut())
                .toList();
        if (!unavailable.isEmpty()) {
            throw ApiException.conflict("sales.product_unavailable", "Some products cannot be sold now")
                    .with("productIds", unavailable);
        }

        Instant now = clock.instant();
        LocalDate businessDate = venue.businessDay().dateOf(now);
        usage.recordSale(venue, now);
        int dailyNumber = numbers.next(venue.getId(), businessDate);
        long total = 0;
        for (NewLine line : request.lines()) {
            total = Math.addExact(total, Math.multiplyExact(byId.get(line.productId()).getPriceCents(), line.quantity()));
        }
        // Flushed first: a concurrent retry with the same client UUID fails here, on the unique key.
        CustomerOrder order = orders.saveAndFlush(new CustomerOrder(venue.getId(), session.deviceId(), shift.getId(),
                session.cashierId(), request.clientOrderId(), businessDate, dailyNumber, total));

        Map<UUID, Category> categoryById = categories.findAllByVenueIdOrderBySortAscNameAsc(venue.getId()).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        List<OrderLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (NewLine line : request.lines()) {
            Product p = byId.get(line.productId());
            Category category = categoryById.get(p.getCategoryId());
            UUID route = p.getRouteId() != null ? p.getRouteId() : category == null ? null : category.getDefaultRouteId();
            lines.add(new OrderLine(order.getId(), venue.getId(), lineNo++, p.getId(), p.getName(), p.getPriceCents(),
                    line.quantity(), route, blankToNull(line.note())));
        }
        orderLines.saveAll(lines);

        Map<UUID, Integer> tracked = new TreeMap<>();
        quantities.forEach((id, quantity) -> {
            if (byId.get(id).isTrackStock()) {
                tracked.put(id, quantity);
            }
        });
        if (!tracked.isEmpty()) {
            StockLedger.Locked locked = ledger.lock(venue, tracked.keySet());
            if (!venue.isAllowNegativeStock()) {
                List<Map<String, Object>> short_ = new ArrayList<>();
                tracked.forEach((id, quantity) -> {
                    int onHand = locked.get(id).onHand();
                    if (onHand < quantity) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("productId", id);
                        item.put("available", Math.max(0, onHand));
                        short_.add(item);
                    }
                });
                if (!short_.isEmpty()) {
                    throw ApiException.conflict("sales.insufficient_stock", "Not enough stock")
                            .with("products", short_);
                }
            }
            StockLedger.Reference reference = StockLedger.Reference.order(order.getId());
            tracked.forEach((id, quantity) -> ledger.remove(locked, id, MovementType.SALE, quantity, reference, null));
        }

        Payment payment = switch (request.method()) {
            case CASH -> {
                if (request.tenderedCents() != null && request.tenderedCents() < total) {
                    throw ApiException.badRequest("sales.insufficient_tender", "The amount tendered is below the total");
                }
                yield Payment.cash(order.getId(), total, request.tenderedCents());
            }
            case MANUAL_CARD -> Payment.manualCard(order.getId(), total);
            case SUMUP_CARD -> throw ApiException.badRequest("sales.method_unavailable",
                    "SumUp card payments arrive in Phase 2");
        };
        payments.save(payment);
        order.markPaid(now);

        boolean cash = payment.getMethod() == Payment.Method.CASH;
        if (request.printReceipt()) {
            prints.queueForRegister(session.deviceId(), order.getId(), PrintJob.Kind.RECEIPT,
                    ReceiptBuilder.build(receipt(venue, session.device().name(), order, lines, payment, cash, false)));
        } else if (cash) {
            // "Senza scontrino": nothing printed, but the drawer still opens for the cash.
            prints.queueForRegister(session.deviceId(), order.getId(), PrintJob.Kind.DRAWER, ReceiptBuilder.drawerKick());
        }
        return new Result(view(order, lines, payment), true);
    }

    // Orders of the day, reprint

    /** The venue's orders of the current business day (or {@code date}), newest first. */
    @Transactional(readOnly = true)
    public List<OrderView> orders(RegisterSession session, LocalDate date) {
        Venue venue = venues.findById(session.venueId()).orElseThrow(() -> ApiException.notFound("Venue"));
        LocalDate day = date != null ? date : venue.businessDay().dateOf(clock.instant());
        List<CustomerOrder> list = orders.findAllByVenueIdAndBusinessDateOrderByDailyNumberDesc(venue.getId(), day);
        return views(list);
    }

    /** Prints (again) an order's receipt on this register's printer: "Ristampa", or later for "Senza scontrino". */
    @Transactional
    public OrderView printReceipt(RegisterSession session, UUID orderId) {
        CustomerOrder order = order(session, orderId);
        if (order.getStatus() != CustomerOrder.Status.PAID) {
            throw ApiException.conflict("sales.not_printable", "Only paid orders have a receipt");
        }
        Venue venue = venues.findById(order.getVenueId()).orElseThrow();
        List<OrderLine> lines = orderLines.findAllByOrderIdOrderByLineNo(orderId);
        Payment payment = payments.findAllByOrderId(orderId).getFirst();
        Optional<PrintJobEntity> job = prints.queueForRegister(session.deviceId(), orderId, PrintJob.Kind.RECEIPT,
                ReceiptBuilder.build(receipt(venue, session.device().name(), order, lines, payment, false,
                        order.isReceiptPrinted())));
        if (job.isEmpty()) {
            throw ApiException.conflict("printing.no_printer", "This register has no printer");
        }
        return view(order);
    }

    @EventListener
    void receiptPrinted(PrintEvents.ReceiptPrinted event) {
        orders.findById(event.orderId()).ifPresent(CustomerOrder::markReceiptPrinted);
    }

    // Void

    /**
     * Voids a paid order with a manager's PIN entered on the register (the cashier's session stays). Each line's
     * goods go back to stock or to waste; the cash goes back to the customer. Audited with the approving manager.
     */
    public OrderView voidOrder(RegisterSession session, UUID orderId, UUID managerId, String pin, String reason,
            List<VoidLine> voidLines) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("sales.void_reason_required", "A reason is required");
        }
        pins.verifyApprover(session.device(), managerId, pin); // commits the attempt, with lockout
        return tx.execute(status -> doVoid(session, orderId, managerId, reason.trim(), voidLines));
    }

    private OrderView doVoid(RegisterSession session, UUID orderId, UUID managerId, String reason,
            List<VoidLine> voidLines) {
        CustomerOrder order = order(session, orderId);
        if (order.getStatus() != CustomerOrder.Status.PAID) {
            throw ApiException.conflict("sales.not_voidable", "Only paid orders can be voided");
        }
        Venue venue = venues.findById(order.getVenueId()).orElseThrow();
        List<OrderLine> lines = orderLines.findAllByOrderIdOrderByLineNo(orderId);
        Map<UUID, OrderLine.VoidDisposition> dispositions = new LinkedHashMap<>();
        for (VoidLine v : voidLines == null ? List.<VoidLine>of() : voidLines) {
            dispositions.put(v.lineId(), v.disposition());
        }
        if (!dispositions.keySet().equals(lines.stream().map(OrderLine::getId).collect(Collectors.toSet()))
                || dispositions.containsValue(null)) {
            throw ApiException.badRequest("sales.void_lines_required",
                    "Say for every line whether goods go back to stock or to waste");
        }
        lines.forEach(l -> l.voidTo(dispositions.get(l.getId())));

        // Stock: give back exactly what the sale took, split between the shelf and the bin.
        Map<UUID, StockMovement> sales = movements.findAllByReferenceIdAndType(orderId, MovementType.SALE).stream()
                .collect(Collectors.toMap(StockMovement::getProductId, Function.identity()));
        Set<UUID> stillTracked = products.findAllById(sales.keySet()).stream()
                .filter(Product::isTrackStock)
                .map(Product::getId)
                .collect(Collectors.toSet());
        if (!stillTracked.isEmpty()) {
            StockLedger.Locked locked = ledger.lock(venue, stillTracked);
            StockLedger.Reference reference = StockLedger.Reference.order(orderId);
            for (UUID productId : stillTracked.stream().sorted().toList()) {
                StockMovement sale = sales.get(productId);
                int sold = -sale.getQuantity();
                long value = -sale.getValueCents();
                int returned = lines.stream()
                        .filter(l -> l.getProductId().equals(productId)
                                && l.getVoidDisposition() == OrderLine.VoidDisposition.RETURN_TO_STOCK)
                        .mapToInt(OrderLine::getQuantity)
                        .sum();
                int wasted = sold - returned;
                long returnedValue = returned == 0 ? 0 : Valuation.share(value, returned, sold);
                long wastedValue = value - returnedValue;
                if (returned > 0) {
                    ledger.postExact(locked, productId, MovementType.RETURN_TO_STOCK, returned, returnedValue,
                            reference, reason);
                }
                if (wasted > 0) {
                    ledger.postExact(locked, productId, MovementType.SALE_REVERSAL, wasted, wastedValue, reference,
                            reason);
                    ledger.postExact(locked, productId, MovementType.WASTE, -wasted, -wastedValue, reference, reason);
                }
            }
        }

        Payment payment = payments.findAllByOrderId(orderId).getFirst();
        payment.refund();
        order.markVoided(clock.instant(), managerId, reason);
        if (payment.getMethod() == Payment.Method.CASH) {
            prints.queueForRegister(session.deviceId(), orderId, PrintJob.Kind.DRAWER, ReceiptBuilder.drawerKick());
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", CustomerOrder.Status.PAID);
        before.put("dailyNumber", order.getDailyNumber());
        before.put("totalCents", order.getTotalCents());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", CustomerOrder.Status.VOIDED);
        after.put("approvedBy", managerId);
        after.put("reason", reason);
        after.put("lines", lines.stream().map(l -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("product", l.getProductName());
            item.put("quantity", l.getQuantity());
            item.put("disposition", l.getVoidDisposition());
            return item;
        }).toList());
        audit.record("order.voided", "customer_order", orderId, before, after);
        return view(order, lines, payment);
    }

    // Helpers

    private Shift openShiftOf(RegisterSession session) {
        Shift shift = shifts.findByDeviceIdAndClosedAtIsNull(session.deviceId())
                .orElseThrow(() -> ApiException.conflict("shift.not_open", "Open a shift before selling"));
        if (!shift.getCashierId().equals(session.cashierId())) {
            throw ApiException.conflict("shift.other_cashier", "The open shift belongs to another cashier");
        }
        return shift;
    }

    private CustomerOrder order(RegisterSession session, UUID orderId) {
        return orders.findByIdAndVenueId(orderId, session.venueId()).orElseThrow(() -> ApiException.notFound("Order"));
    }

    private OrderView view(CustomerOrder order) {
        return views(List.of(order)).getFirst();
    }

    private OrderView view(CustomerOrder order, List<OrderLine> lines, Payment payment) {
        List<PrintJobEntity> jobs = prints.jobsOfOrders(List.of(order.getId()));
        return new OrderView(order, lines, payment, receiptState(jobs), receiptError(jobs));
    }

    private List<OrderView> views(List<CustomerOrder> list) {
        List<UUID> ids = list.stream().map(CustomerOrder::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<OrderLine>> linesByOrder = orderLines.findAllByOrderIdInOrderByLineNo(ids).stream()
                .collect(Collectors.groupingBy(OrderLine::getOrderId));
        Map<UUID, Payment> paymentByOrder = payments.findAllByOrderIdIn(ids).stream()
                .collect(Collectors.toMap(Payment::getOrderId, Function.identity(), (a, b) -> a));
        Map<UUID, List<PrintJobEntity>> jobsByOrder = prints.jobsOfOrders(ids).stream()
                .collect(Collectors.groupingBy(PrintJobEntity::getOrderId));
        return list.stream().map(o -> {
            List<PrintJobEntity> jobs = jobsByOrder.getOrDefault(o.getId(), List.of());
            return new OrderView(o, linesByOrder.getOrDefault(o.getId(), List.of()), paymentByOrder.get(o.getId()),
                    receiptState(jobs), receiptError(jobs));
        }).toList();
    }

    private static ReceiptState receiptState(List<PrintJobEntity> jobs) {
        return lastReceipt(jobs).map(j -> ReceiptState.valueOf(j.getStatus().name())).orElse(ReceiptState.NOT_REQUESTED);
    }

    private static String receiptError(List<PrintJobEntity> jobs) {
        return lastReceipt(jobs).map(PrintJobEntity::getLastError).orElse(null);
    }

    private static Optional<PrintJobEntity> lastReceipt(List<PrintJobEntity> jobs) {
        return jobs.stream()
                .filter(j -> j.getKind() == PrintJob.Kind.RECEIPT)
                .max(Comparator.comparing(PrintJobEntity::getCreatedAt));
    }

    private static ReceiptBuilder.Receipt receipt(Venue venue, String registerName, CustomerOrder order,
            List<OrderLine> lines, Payment payment, boolean kickDrawer, boolean reprint) {
        return new ReceiptBuilder.Receipt(venue.getName(), venue.getReceiptHeader(), venue.getReceiptFooter(),
                ZoneId.of(venue.getTimeZone()), order.getDailyNumber(),
                order.getPaidAt() == null ? order.getCreatedAt() : order.getPaidAt(), registerName,
                lines.stream()
                        .map(l -> new ReceiptBuilder.Line(l.getProductName(), l.getQuantity(), l.getUnitPriceCents(),
                                l.getLineTotalCents(), l.getNote()))
                        .toList(),
                order.getTotalCents(), ReceiptBuilder.PaymentMethod.valueOf(payment.getMethod().name()),
                payment.getTenderedCents(), payment.getChangeCents(), kickDrawer, reprint);
    }

    private static void validate(NewOrder request) {
        if (request.clientOrderId() == null) {
            throw ApiException.badRequest("sales.client_id_required", "The register must send a client order id");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw ApiException.badRequest("sales.empty_order", "The order has no lines");
        }
        if (request.lines().size() > MAX_LINES) {
            throw ApiException.badRequest("sales.too_many_lines", "Too many lines");
        }
        for (NewLine line : request.lines()) {
            if (line.productId() == null || line.quantity() < 1 || line.quantity() > MAX_QUANTITY) {
                throw ApiException.badRequest("sales.invalid_quantity", "Quantities go from 1 to " + MAX_QUANTITY);
            }
        }
        if (request.method() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sales.method_required", "Choose a payment method");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
