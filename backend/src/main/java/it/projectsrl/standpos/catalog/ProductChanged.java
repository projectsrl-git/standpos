package it.projectsrl.standpos.catalog;

import java.util.UUID;

/** Published inside the transaction that created or edited a product, so its availability reaches the registers. */
public record ProductChanged(UUID venueId, UUID productId) {}
