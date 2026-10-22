package it.projectsrl.standpos.catalog;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Which ticket a line goes on ("Cucina", "Bar"). The ticket printer is added with printing in Phase 1. */
@Entity
@Table(name = "route")
public class Route extends TenantOwnedEntity {

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int sort;

    protected Route() {}

    public Route(UUID venueId, String name, int sort) {
        this.venueId = venueId;
        update(name, sort);
    }

    public void update(String name, int sort) {
        this.name = name;
        this.sort = sort;
    }

    public UUID getVenueId() {
        return venueId;
    }

    public String getName() {
        return name;
    }

    public int getSort() {
        return sort;
    }
}
