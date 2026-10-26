package it.projectsrl.standpos.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findAllByVenueIdOrderBySortAscNameAsc(UUID venueId);

    Optional<Category> findByIdAndVenueId(UUID id, UUID venueId);
}
