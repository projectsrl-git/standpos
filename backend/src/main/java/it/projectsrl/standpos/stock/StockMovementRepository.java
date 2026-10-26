package it.projectsrl.standpos.stock;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    List<StockMovement> findAllByVenueIdOrderByCreatedAtDesc(UUID venueId, Pageable page);

    List<StockMovement> findAllByProductIdOrderByCreatedAtDesc(UUID productId, Pageable page);

    List<StockMovement> findAllByReferenceIdAndType(UUID referenceId, MovementType type);

    boolean existsByProductId(UUID productId);

    /** Sum of every movement of each product of a venue: must equal the level (ledger invariant). */
    @Query("select m.productId, sum(m.quantity), sum(m.valueCents) from StockMovement m"
            + " where m.venueId = :venueId group by m.productId")
    List<Object[]> totalsByProduct(UUID venueId);
}
