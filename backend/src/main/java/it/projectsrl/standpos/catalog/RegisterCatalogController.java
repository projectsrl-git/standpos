package it.projectsrl.standpos.catalog;

import it.projectsrl.standpos.auth.Authz;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.venue.Venue;
import it.projectsrl.standpos.venue.VenueRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The product grid shown on the register, for the logged-in cashier's venue. Read-only in Phase 0. */
@RestController
@RequestMapping("/api/v1/register")
class RegisterCatalogController {

    record GridProduct(UUID id, String name, long priceCents, boolean soldOut) {}

    record GridCategory(UUID id, String name, String colour, List<GridProduct> products) {}

    record Grid(UUID venueId, String venueName, List<GridCategory> categories) {}

    private final VenueRepository venues;
    private final CategoryRepository categories;
    private final ProductRepository products;

    RegisterCatalogController(VenueRepository venues, CategoryRepository categories, ProductRepository products) {
        this.venues = venues;
        this.categories = categories;
        this.products = products;
    }

    @GetMapping("/catalogue")
    @PreAuthorize("@authz.isCashier()")
    @Transactional(readOnly = true)
    public Grid catalogue() {
        UUID venueId = Authz.requirePrincipal().venueId();
        Venue venue = venues.findById(venueId).orElseThrow(() -> ApiException.notFound("Venue"));
        Map<UUID, List<GridProduct>> byCategory = products.findAllByVenueIdOrderBySortAscNameAsc(venueId).stream()
                .filter(Product::isActive)
                .collect(Collectors.groupingBy(Product::getCategoryId, java.util.LinkedHashMap::new,
                        Collectors.mapping(
                                p -> new GridProduct(p.getId(), p.getName(), p.getPriceCents(), p.isSoldOut()),
                                Collectors.toList())));
        List<GridCategory> grid = categories.findAllByVenueIdOrderBySortAscNameAsc(venueId).stream()
                .filter(c -> byCategory.containsKey(c.getId()))
                .map(c -> new GridCategory(c.getId(), c.getName(), c.getColour(), byCategory.get(c.getId())))
                .toList();
        return new Grid(venue.getId(), venue.getName(), grid);
    }
}
