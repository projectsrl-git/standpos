package it.projectsrl.standpos.catalog;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A product button. Price in integer euro cents; stock tracking fields are used from Phase 1. */
@Entity
@Table(name = "product")
public class Product extends TenantOwnedEntity {

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "route_id")
    private UUID routeId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "track_stock", nullable = false)
    private boolean trackStock;

    @Column(name = "low_threshold")
    private Integer lowThreshold;

    @Column(name = "sold_out", nullable = false)
    private boolean soldOut;

    @Column(nullable = false)
    private int sort;

    protected Product() {}

    public Product(UUID venueId, UUID categoryId, String name, long priceCents) {
        this.venueId = venueId;
        this.categoryId = categoryId;
        this.name = name;
        this.priceCents = priceCents;
    }

    public void update(UUID categoryId, String name, long priceCents, UUID routeId, boolean active, boolean trackStock,
            Integer lowThreshold, boolean soldOut, int sort) {
        this.categoryId = categoryId;
        this.name = name;
        this.priceCents = priceCents;
        this.routeId = routeId;
        this.active = active;
        this.trackStock = trackStock;
        this.lowThreshold = lowThreshold;
        this.soldOut = soldOut;
        this.sort = sort;
    }

    public UUID getVenueId() {
        return venueId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getName() {
        return name;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public UUID getRouteId() {
        return routeId;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isTrackStock() {
        return trackStock;
    }

    public Integer getLowThreshold() {
        return lowThreshold;
    }

    public boolean isSoldOut() {
        return soldOut;
    }

    public int getSort() {
        return sort;
    }
}
