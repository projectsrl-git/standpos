package it.projectsrl.standpos.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findAllByVenueIdOrderBySortAscNameAsc(UUID venueId);

    Optional<Product> findByIdAndVenueId(UUID id, UUID venueId);

    boolean existsByCategoryId(UUID categoryId);

    boolean existsByRouteId(UUID routeId);
}
