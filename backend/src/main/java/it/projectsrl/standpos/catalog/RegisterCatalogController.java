package it.projectsrl.standpos.catalog;

import it.projectsrl.standpos.auth.Authz;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.stock.Availability;
import it.projectsrl.standpos.stock.StockService;
import it.projectsrl.standpos.venue.Venue;
import it.projectsrl.standpos.venue.VenueRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The product grid shown on the register, for the logged-in cashier's venue, with each product's availability:
 * sold out (by hand, or no stock left) and, below the low threshold, the remaining quantity. Later changes arrive
 * over WebSocket on {@code /topic/venues/{venueId}/stock}.
 */
@RestController
@RequestMapping("/api/v1/register")
class RegisterCatalogController {

    /** {@code onHand} is null for untracked products; {@code low} says the register shows the remaining count. */
    record GridProduct(UUID id, String name, long priceCents, boolean soldOut, Integer onHand, boolean low) {}

    record GridCategory(UUID id, String name, String colour, List<GridProduct> products) {}

    record Grid(UUID venueId, String venueName, List<GridCategory> categories) {}

    private final VenueRepository venues;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final StockService stock;

    RegisterCatalogController(VenueRepository venues, CategoryRepository categories, ProductRepository products,
            StockService stock) {
        this.venues = venues;
        this.categories = categories;
        this.products = products;
        this.stock = stock;
    }

    @GetMapping("/catalogue")
    @PreAuthorize("@authz.isCashier()")
    @Transactional(readOnly = true)
    public Grid catalogue() {
        UUID venueId = Authz.requirePrincipal().venueId();
        Venue venue = venues.findById(venueId).orElseThrow(() -> ApiException.notFound("Venue"));
        List<Product> active = products.findAllByVenueIdOrderBySortAscNameAsc(venueId).stream()
                .filter(Product::isActive)
                .toList();
        Map<UUID, Availability> availability = stock.availability(venue, active);
        Map<UUID, List<GridProduct>> byCategory = active.stream()
                .collect(Collectors.groupingBy(Product::getCategoryId, LinkedHashMap::new,
                        Collectors.mapping(p -> {
                            Availability a = availability.get(p.getId());
                            return new GridProduct(p.getId(), p.getName(), p.getPriceCents(), a.soldOut(),
                                    a.onHand(), a.low());
                        }, Collectors.toList())));
        List<GridCategory> grid = categories.findAllByVenueIdOrderBySortAscNameAsc(venueId).stream()
                .filter(c -> byCategory.containsKey(c.getId()))
                .map(c -> new GridCategory(c.getId(), c.getName(), c.getColour(), byCategory.get(c.getId())))
                .toList();
        return new Grid(venue.getId(), venue.getName(), grid);
    }
}
