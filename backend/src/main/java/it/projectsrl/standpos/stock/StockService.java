package it.projectsrl.standpos.stock;

import it.projectsrl.standpos.audit.AuditService;
import it.projectsrl.standpos.auth.Authz;
import it.projectsrl.standpos.auth.StandposPrincipal;
import it.projectsrl.standpos.catalog.Product;
import it.projectsrl.standpos.catalog.ProductChanged;
import it.projectsrl.standpos.catalog.ProductRepository;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.venue.Venue;
import it.projectsrl.standpos.venue.VenueRepository;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stock levels, the low-stock list, movements, adjustments and waste, sold-out toggles and stock counts. */
@Service
public class StockService {

    public record LevelView(UUID productId, String productName, UUID categoryId, boolean active, int onHand,
            long valueCents, long averageCostCents, Integer lowThreshold, boolean low, boolean soldOut,
            boolean manualSoldOut) {}

    public record CountView(StockCount count, List<StockCountLine> lines) {}

    public record CountedQuantity(UUID productId, Integer counted) {}

    private final VenueRepository venues;
    private final ProductRepository products;
    private final StockLevelRepository levels;
    private final StockMovementRepository movements;
    private final StockCountRepository counts;
    private final StockCountLineRepository countLines;
    private final StockLedger ledger;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public StockService(VenueRepository venues, ProductRepository products, StockLevelRepository levels,
            StockMovementRepository movements, StockCountRepository counts, StockCountLineRepository countLines,
            StockLedger ledger, AuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.venues = venues;
        this.products = products;
        this.levels = levels;
        this.movements = movements;
        this.counts = counts;
        this.countLines = countLines;
        this.ledger = ledger;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // Levels

    /** Every tracked product of the venue with its quantity, value and availability. */
    @Transactional(readOnly = true)
    public List<LevelView> levels(UUID venueId) {
        Venue venue = venue(venueId);
        Map<UUID, StockLevel> byProduct = levelsByProduct(venueId);
        return products.findAllByVenueIdOrderBySortAscNameAsc(venueId).stream()
                .filter(Product::isTrackStock)
                .map(p -> view(venue, p, byProduct.get(p.getId())))
                .toList();
    }

    /** Products at or below their low threshold, or sold out, most urgent first. */
    @Transactional(readOnly = true)
    public List<LevelView> lowStock(UUID venueId) {
        Venue venue = venue(venueId);
        Map<UUID, StockLevel> byProduct = levelsByProduct(venueId);
        return products.findAllByVenueIdOrderBySortAscNameAsc(venueId).stream()
                .filter(Product::isActive)
                .filter(p -> p.isTrackStock() || p.isSoldOut())
                .map(p -> view(venue, p, byProduct.get(p.getId())))
                .filter(v -> v.low() || v.soldOut())
                .sorted(Comparator.comparingInt(LevelView::onHand))
                .toList();
    }

    /** Availability of every product of the venue, for the register grid. */
    @Transactional(readOnly = true)
    public Map<UUID, Availability> availability(Venue venue, List<Product> venueProducts) {
        Map<UUID, StockLevel> byProduct = levelsByProduct(venue.getId());
        Map<UUID, Availability> result = new LinkedHashMap<>();
        for (Product p : venueProducts) {
            result.put(p.getId(), availability(venue, p, byProduct.get(p.getId())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<StockMovement> movements(UUID venueId, UUID productId, int limit) {
        venue(venueId);
        PageRequest page = PageRequest.of(0, Math.clamp(limit, 1, 500));
        if (productId != null) {
            product(venueId, productId);
            return movements.findAllByProductIdOrderByCreatedAtDesc(productId, page);
        }
        return movements.findAllByVenueIdOrderByCreatedAtDesc(venueId, page);
    }

    // Adjustments and waste

    /**
     * Breakage, gifts, corrections ({@code ADJUSTMENT}, signed quantity) or goods thrown away ({@code WASTE},
     * positive quantity). The reason is mandatory; the change is audited.
     */
    @Transactional
    public LevelView adjust(UUID venueId, UUID productId, MovementType type, int quantity, String reason) {
        if (type != MovementType.ADJUSTMENT && type != MovementType.WASTE) {
            throw ApiException.badRequest("stock.invalid_type", "Only ADJUSTMENT or WASTE can be entered by hand");
        }
        if (quantity == 0 || (type == MovementType.WASTE && quantity < 0)) {
            throw ApiException.badRequest("stock.invalid_quantity", "Quantity must be non-zero (positive for waste)");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("stock.reason_required", "A reason is required");
        }
        Venue venue = venue(venueId);
        Product product = product(venueId, productId);
        StockLedger.Locked locked = ledger.lock(venue, List.of(productId));
        int before = locked.get(productId).onHand();
        int signedQuantity = type == MovementType.WASTE ? -quantity : quantity;
        if (before + signedQuantity < 0 && !venue.isAllowNegativeStock()) {
            throw ApiException.conflict("stock.insufficient", "Not enough stock for this change")
                    .with("available", before);
        }
        long value;
        if (signedQuantity > 0) {
            value = ledger.receive(locked, productId, type, signedQuantity, null, null, reason.trim());
        } else {
            value = -ledger.remove(locked, productId, type, -signedQuantity, null, reason.trim());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("type", type);
        after.put("quantity", signedQuantity);
        after.put("valueCents", value);
        after.put("onHandBefore", before);
        after.put("onHandAfter", locked.get(productId).onHand());
        after.put("reason", reason.trim());
        audit.record("stock.adjusted", "product", productId, null, after);
        StockLedger.Level level = locked.get(productId);
        Availability availability = Availability.of(true, level.onHand(), product.isSoldOut(),
                product.getLowThreshold(), venue.isAllowNegativeStock());
        return new LevelView(product.getId(), product.getName(), product.getCategoryId(), product.isActive(),
                level.onHand(), level.valueCents(), Valuation.averageCost(level.onHand(), level.valueCents()),
                product.getLowThreshold(), availability.low(), availability.soldOut(), product.isSoldOut());
    }

    /** A catalogue edit may change tracking, threshold or the manual flag: tell the registers. */
    @EventListener
    public void onProductChanged(ProductChanged event) {
        venues.findById(event.venueId()).ifPresent(venue -> products.findById(event.productId())
                .ifPresent(product -> publish(venue, product)));
    }

    // Sold out by hand

    @Transactional
    public LevelView setSoldOut(UUID venueId, UUID productId, boolean soldOut) {
        Venue venue = venue(venueId);
        Product product = product(venueId, productId);
        product.setSoldOut(soldOut);
        products.saveAndFlush(product);
        LevelView view = view(venue, product, levels.findByProductId(productId).orElse(null));
        events.publishEvent(new StockChanged(venueId, productId, product.isTrackStock() ? view.onHand() : null,
                view.soldOut(), view.low()));
        return view;
    }

    private void publish(Venue venue, Product product) {
        Availability availability = availability(venue, product, levels.findByProductId(product.getId()).orElse(null));
        events.publishEvent(StockChanged.of(venue.getId(), product.getId(), availability));
    }

    // Counts

    @Transactional(readOnly = true)
    public List<StockCount> counts(UUID venueId) {
        venue(venueId);
        return counts.findAllByVenueIdOrderByCreatedAtDesc(venueId);
    }

    @Transactional(readOnly = true)
    public CountView count(UUID venueId, UUID countId) {
        StockCount count = countEntity(venueId, countId);
        return new CountView(count, countLines.findAllByStockCountId(countId));
    }

    /** Opens a count listing every active tracked product with its current quantity. */
    @Transactional
    public CountView openCount(UUID venueId, String notes) {
        venue(venueId);
        Map<UUID, StockLevel> byProduct = levelsByProduct(venueId);
        StockCount count = counts.saveAndFlush(new StockCount(venueId, blankToNull(notes), currentUserId()));
        List<StockCountLine> lines = products.findAllByVenueIdOrderBySortAscNameAsc(venueId).stream()
                .filter(p -> p.isTrackStock() && p.isActive())
                .map(p -> new StockCountLine(count.getId(), venueId, p.getId(),
                        byProduct.containsKey(p.getId()) ? byProduct.get(p.getId()).getOnHand() : 0))
                .toList();
        countLines.saveAll(lines);
        return new CountView(count, lines);
    }

    @Transactional
    public CountView enterCounts(UUID venueId, UUID countId, List<CountedQuantity> quantities) {
        StockCount count = countEntity(venueId, countId);
        count.requireDraft();
        Map<UUID, StockCountLine> lines = countLines.findAllByStockCountId(countId).stream()
                .collect(Collectors.toMap(StockCountLine::getProductId, Function.identity()));
        for (CountedQuantity q : quantities) {
            StockCountLine line = lines.get(q.productId());
            if (line == null) {
                throw ApiException.notFound("Count line");
            }
            if (q.counted() != null && q.counted() < 0) {
                throw ApiException.badRequest("stock.invalid_quantity", "Counted quantity cannot be negative");
            }
            line.count(q.counted());
        }
        return new CountView(count, List.copyOf(lines.values()));
    }

    /**
     * Writes a COUNT movement for every counted product whose quantity differs from the stock at this moment, then
     * freezes the count. Products left blank are not touched.
     */
    @Transactional
    public CountView postCount(UUID venueId, UUID countId) {
        Venue venue = venue(venueId);
        StockCount count = countEntity(venueId, countId);
        count.requireDraft();
        List<StockCountLine> lines = countLines.findAllByStockCountId(countId).stream()
                .filter(l -> l.getCounted() != null)
                .toList();
        StockLedger.Locked locked = ledger.lock(venue, lines.stream().map(StockCountLine::getProductId).toList());
        StockLedger.Reference reference = StockLedger.Reference.stockCount(countId);
        int changed = 0;
        for (StockCountLine line : lines) {
            int difference = line.getCounted() - locked.get(line.getProductId()).onHand();
            if (difference > 0) {
                ledger.receive(locked, line.getProductId(), MovementType.COUNT, difference, null, reference, null);
            } else if (difference < 0) {
                ledger.remove(locked, line.getProductId(), MovementType.COUNT, -difference, reference, null);
            }
            if (difference != 0) {
                changed++;
            }
            line.posted(difference);
        }
        count.post(clock.instant(), currentUserId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("counted", lines.size());
        after.put("changed", changed);
        audit.record("stock_count.posted", "stock_count", countId, null, after);
        return new CountView(count, countLines.findAllByStockCountId(countId));
    }

    @Transactional
    public void deleteCount(UUID venueId, UUID countId) {
        StockCount count = countEntity(venueId, countId);
        count.requireDraft();
        counts.delete(count);
    }

    // Helpers

    private LevelView view(Venue venue, Product p, StockLevel level) {
        Availability availability = availability(venue, p, level);
        int onHand = level == null ? 0 : level.getOnHand();
        long value = level == null ? 0 : level.getValueCents();
        return new LevelView(p.getId(), p.getName(), p.getCategoryId(), p.isActive(), onHand, value,
                Valuation.averageCost(onHand, value), p.getLowThreshold(), availability.low(), availability.soldOut(),
                p.isSoldOut());
    }

    private static Availability availability(Venue venue, Product p, StockLevel level) {
        return Availability.of(p.isTrackStock(), level == null ? 0 : level.getOnHand(), p.isSoldOut(),
                p.getLowThreshold(), venue.isAllowNegativeStock());
    }

    private Map<UUID, StockLevel> levelsByProduct(UUID venueId) {
        return levels.findAllByVenueId(venueId).stream()
                .collect(Collectors.toMap(StockLevel::getProductId, Function.identity()));
    }

    private Venue venue(UUID venueId) {
        return venues.findById(venueId).orElseThrow(() -> ApiException.notFound("Venue"));
    }

    private Product product(UUID venueId, UUID productId) {
        return products.findByIdAndVenueId(productId, venueId).orElseThrow(() -> ApiException.notFound("Product"));
    }

    private StockCount countEntity(UUID venueId, UUID countId) {
        return counts.findByIdAndVenueId(countId, venueId).orElseThrow(() -> ApiException.notFound("Stock count"));
    }

    static UUID currentUserId() {
        return Authz.principal().map(StandposPrincipal::userId).orElse(null);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
