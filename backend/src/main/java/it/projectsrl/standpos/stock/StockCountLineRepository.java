package it.projectsrl.standpos.stock;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface StockCountLineRepository extends JpaRepository<StockCountLine, UUID> {

    List<StockCountLine> findAllByStockCountId(UUID stockCountId);

    boolean existsByProductId(UUID productId);
}
