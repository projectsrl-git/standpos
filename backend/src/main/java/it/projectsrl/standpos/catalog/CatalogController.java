package it.projectsrl.standpos.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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

/** Catalogue management for owners and the venue's managers. */
@RestController
@RequestMapping("/api/v1/venues/{venueId}")
class CatalogController {

    record RouteRequest(@NotBlank @Size(max = 60) String name, int sort) {}

    record RouteResponse(UUID id, String name, int sort) {

        static RouteResponse of(Route r) {
            return new RouteResponse(r.getId(), r.getName(), r.getSort());
        }
    }

    record CategoryRequest(
            @NotBlank @Size(max = 60) String name,
            @NotBlank @Pattern(regexp = "#[0-9A-Fa-f]{6}") String colour,
            int sort,
            UUID defaultRouteId) {}

    record CategoryResponse(UUID id, String name, String colour, int sort, UUID defaultRouteId) {

        static CategoryResponse of(Category c) {
            return new CategoryResponse(c.getId(), c.getName(), c.getColour(), c.getSort(), c.getDefaultRouteId());
        }
    }

    record ProductRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 80) String name,
            @PositiveOrZero long priceCents,
            UUID routeId,
            boolean active,
            boolean trackStock,
            @PositiveOrZero Integer lowThreshold,
            boolean soldOut,
            int sort) {}

    record ProductResponse(UUID id, UUID categoryId, String name, long priceCents, UUID routeId, boolean active,
            boolean trackStock, Integer lowThreshold, boolean soldOut, int sort) {

        static ProductResponse of(Product p) {
            return new ProductResponse(p.getId(), p.getCategoryId(), p.getName(), p.getPriceCents(), p.getRouteId(),
                    p.isActive(), p.isTrackStock(), p.getLowThreshold(), p.isSoldOut(), p.getSort());
        }
    }

    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/routes")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    List<RouteResponse> routes(@PathVariable UUID venueId) {
        return catalog.routes(venueId).stream().map(RouteResponse::of).toList();
    }

    @PostMapping("/routes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    RouteResponse createRoute(@PathVariable UUID venueId, @Valid @RequestBody RouteRequest r) {
        return RouteResponse.of(catalog.createRoute(venueId, new CatalogService.RouteData(r.name().trim(), r.sort())));
    }

    @PutMapping("/routes/{routeId}")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    RouteResponse updateRoute(@PathVariable UUID venueId, @PathVariable UUID routeId,
            @Valid @RequestBody RouteRequest r) {
        return RouteResponse.of(
                catalog.updateRoute(venueId, routeId, new CatalogService.RouteData(r.name().trim(), r.sort())));
    }

    @DeleteMapping("/routes/{routeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    void deleteRoute(@PathVariable UUID venueId, @PathVariable UUID routeId) {
        catalog.deleteRoute(venueId, routeId);
    }

    @GetMapping("/categories")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    List<CategoryResponse> categories(@PathVariable UUID venueId) {
        return catalog.categories(venueId).stream().map(CategoryResponse::of).toList();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    CategoryResponse createCategory(@PathVariable UUID venueId, @Valid @RequestBody CategoryRequest r) {
        return CategoryResponse.of(catalog.createCategory(venueId, category(r)));
    }

    @PutMapping("/categories/{categoryId}")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    CategoryResponse updateCategory(@PathVariable UUID venueId, @PathVariable UUID categoryId,
            @Valid @RequestBody CategoryRequest r) {
        return CategoryResponse.of(catalog.updateCategory(venueId, categoryId, category(r)));
    }

    @DeleteMapping("/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    void deleteCategory(@PathVariable UUID venueId, @PathVariable UUID categoryId) {
        catalog.deleteCategory(venueId, categoryId);
    }

    @GetMapping("/products")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    List<ProductResponse> products(@PathVariable UUID venueId) {
        return catalog.products(venueId).stream().map(ProductResponse::of).toList();
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    ProductResponse createProduct(@PathVariable UUID venueId, @Valid @RequestBody ProductRequest r) {
        return ProductResponse.of(catalog.createProduct(venueId, product(r)));
    }

    @PutMapping("/products/{productId}")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    ProductResponse updateProduct(@PathVariable UUID venueId, @PathVariable UUID productId,
            @Valid @RequestBody ProductRequest r) {
        return ProductResponse.of(catalog.updateProduct(venueId, productId, product(r)));
    }

    @DeleteMapping("/products/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    void deleteProduct(@PathVariable UUID venueId, @PathVariable UUID productId) {
        catalog.deleteProduct(venueId, productId);
    }

    private static CatalogService.CategoryData category(CategoryRequest r) {
        return new CatalogService.CategoryData(r.name().trim(), r.colour().toUpperCase(), r.sort(), r.defaultRouteId());
    }

    private static CatalogService.ProductData product(ProductRequest r) {
        return new CatalogService.ProductData(r.categoryId(), r.name().trim(), r.priceCents(), r.routeId(), r.active(),
                r.trackStock(), r.lowThreshold(), r.soldOut(), r.sort());
    }
}
