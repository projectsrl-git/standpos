package it.projectsrl.standpos.catalog;

import it.projectsrl.standpos.audit.AuditService;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.venue.VenueRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Routes, categories and products of one venue. Price changes are audited. */
@Service
public class CatalogService {

    public record RouteData(String name, int sort) {}

    public record CategoryData(String name, String colour, int sort, UUID defaultRouteId) {}

    public record ProductData(UUID categoryId, String name, long priceCents, UUID routeId, boolean active,
            boolean trackStock, Integer lowThreshold, boolean soldOut, int sort) {}

    private final VenueRepository venues;
    private final RouteRepository routes;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final AuditService audit;

    public CatalogService(VenueRepository venues, RouteRepository routes, CategoryRepository categories,
            ProductRepository products, AuditService audit) {
        this.venues = venues;
        this.routes = routes;
        this.categories = categories;
        this.products = products;
        this.audit = audit;
    }

    // Routes

    @Transactional(readOnly = true)
    public List<Route> routes(UUID venueId) {
        requireVenue(venueId);
        return routes.findAllByVenueIdOrderBySortAscNameAsc(venueId);
    }

    @Transactional
    public Route createRoute(UUID venueId, RouteData data) {
        requireVenue(venueId);
        return routes.saveAndFlush(new Route(venueId, data.name(), data.sort()));
    }

    @Transactional
    public Route updateRoute(UUID venueId, UUID routeId, RouteData data) {
        Route route = route(venueId, routeId);
        route.update(data.name(), data.sort());
        return routes.saveAndFlush(route);
    }

    @Transactional
    public void deleteRoute(UUID venueId, UUID routeId) {
        Route route = route(venueId, routeId);
        boolean used = products.existsByRouteId(routeId)
                || categories.findAllByVenueIdOrderBySortAscNameAsc(venueId).stream()
                        .anyMatch(c -> routeId.equals(c.getDefaultRouteId()));
        if (used) {
            throw ApiException.conflict("catalog.route_in_use", "The route is used by categories or products");
        }
        routes.delete(route);
    }

    // Categories

    @Transactional(readOnly = true)
    public List<Category> categories(UUID venueId) {
        requireVenue(venueId);
        return categories.findAllByVenueIdOrderBySortAscNameAsc(venueId);
    }

    @Transactional
    public Category createCategory(UUID venueId, CategoryData data) {
        requireVenue(venueId);
        checkRoute(venueId, data.defaultRouteId());
        return categories.saveAndFlush(
                new Category(venueId, data.name(), data.colour(), data.sort(), data.defaultRouteId()));
    }

    @Transactional
    public Category updateCategory(UUID venueId, UUID categoryId, CategoryData data) {
        Category category = category(venueId, categoryId);
        checkRoute(venueId, data.defaultRouteId());
        category.update(data.name(), data.colour(), data.sort(), data.defaultRouteId());
        return categories.saveAndFlush(category);
    }

    @Transactional
    public void deleteCategory(UUID venueId, UUID categoryId) {
        Category category = category(venueId, categoryId);
        if (products.existsByCategoryId(categoryId)) {
            throw ApiException.conflict("catalog.category_not_empty", "The category still has products");
        }
        categories.delete(category);
    }

    // Products

    @Transactional(readOnly = true)
    public List<Product> products(UUID venueId) {
        requireVenue(venueId);
        return products.findAllByVenueIdOrderBySortAscNameAsc(venueId);
    }

    @Transactional
    public Product createProduct(UUID venueId, ProductData data) {
        requireVenue(venueId);
        category(venueId, data.categoryId());
        checkRoute(venueId, data.routeId());
        Product product = new Product(venueId, data.categoryId(), data.name(), data.priceCents());
        apply(product, data);
        products.saveAndFlush(product);
        audit.record("product.created", "product", product.getId(), null, price(product.getPriceCents()));
        return product;
    }

    @Transactional
    public Product updateProduct(UUID venueId, UUID productId, ProductData data) {
        Product product = product(venueId, productId);
        category(venueId, data.categoryId());
        checkRoute(venueId, data.routeId());
        long before = product.getPriceCents();
        apply(product, data);
        products.saveAndFlush(product);
        if (before != product.getPriceCents()) {
            audit.record("product.price_changed", "product", productId, price(before), price(product.getPriceCents()));
        }
        return product;
    }

    /** Hard delete is safe while no order references products (Phase 0); later phases deactivate instead. */
    @Transactional
    public void deleteProduct(UUID venueId, UUID productId) {
        Product product = product(venueId, productId);
        products.delete(product);
        audit.record("product.deleted", "product", productId, price(product.getPriceCents()), null);
    }

    private static void apply(Product product, ProductData d) {
        product.update(d.categoryId(), d.name(), d.priceCents(), d.routeId(), d.active(), d.trackStock(),
                d.lowThreshold(), d.soldOut(), d.sort());
    }

    private static Map<String, Object> price(long cents) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("priceCents", cents);
        return data;
    }

    private void requireVenue(UUID venueId) {
        if (venues.findById(venueId).isEmpty()) {
            throw ApiException.notFound("Venue");
        }
    }

    private void checkRoute(UUID venueId, UUID routeId) {
        if (routeId != null) {
            route(venueId, routeId);
        }
    }

    private Route route(UUID venueId, UUID routeId) {
        return routes.findByIdAndVenueId(routeId, venueId).orElseThrow(() -> ApiException.notFound("Route"));
    }

    private Category category(UUID venueId, UUID categoryId) {
        return categories.findByIdAndVenueId(categoryId, venueId).orElseThrow(() -> ApiException.notFound("Category"));
    }

    private Product product(UUID venueId, UUID productId) {
        return products.findByIdAndVenueId(productId, venueId).orElseThrow(() -> ApiException.notFound("Product"));
    }
}
