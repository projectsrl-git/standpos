package it.projectsrl.standpos.catalog;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A coloured group of product buttons on the register, with the route its products go to by default. */
@Entity
@Table(name = "category")
public class Category extends TenantOwnedEntity {

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String colour;

    @Column(nullable = false)
    private int sort;

    @Column(name = "default_route_id")
    private UUID defaultRouteId;

    protected Category() {}

    public Category(UUID venueId, String name, String colour, int sort, UUID defaultRouteId) {
        this.venueId = venueId;
        update(name, colour, sort, defaultRouteId);
    }

    public void update(String name, String colour, int sort, UUID defaultRouteId) {
        this.name = name;
        this.colour = colour;
        this.sort = sort;
        this.defaultRouteId = defaultRouteId;
    }

    public UUID getVenueId() {
        return venueId;
    }

    public String getName() {
        return name;
    }

    public String getColour() {
        return colour;
    }

    public int getSort() {
        return sort;
    }

    public UUID getDefaultRouteId() {
        return defaultRouteId;
    }
}
