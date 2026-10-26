package it.projectsrl.standpos.stock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface StockCountRepository extends JpaRepository<StockCount, UUID> {

    List<StockCount> findAllByVenueIdOrderByCreatedAtDesc(UUID venueId);

    Optional<StockCount> findByIdAndVenueId(UUID id, UUID venueId);
}
