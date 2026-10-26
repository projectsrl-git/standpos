package it.projectsrl.standpos.stock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    List<GoodsReceipt> findAllByVenueIdOrderByReceivedOnDescCreatedAtDesc(UUID venueId);

    Optional<GoodsReceipt> findByIdAndVenueId(UUID id, UUID venueId);

    boolean existsBySupplierId(UUID supplierId);
}
