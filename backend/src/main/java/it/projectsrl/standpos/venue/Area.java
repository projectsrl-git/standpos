package it.projectsrl.standpos.venue;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Optional grouping of stations inside a venue ("Cucina", "Bar"). No API in Phase 0. */
@Entity
@Table(name = "area")
public class Area extends TenantOwnedEntity {

    @Column(name = "venue_id", nullable = false)
    private UUID venueId;

    @Column(nullable = false)
    private String name;

    protected Area() {}

    public Area(UUID venueId, String name) {
        this.venueId = venueId;
        this.name = name;
    }

    public UUID getVenueId() {
        return venueId;
    }

    public String getName() {
        return name;
    }
}
