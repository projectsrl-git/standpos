package it.projectsrl.standpos.stock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    List<StockLevel> findAllByVenueId(UUID venueId);

    Optional<StockLevel> findByProductId(UUID productId);
}
