package it.projectsrl.standpos.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface RouteRepository extends JpaRepository<Route, UUID> {

    List<Route> findAllByVenueIdOrderBySortAscNameAsc(UUID venueId);

    Optional<Route> findByIdAndVenueId(UUID id, UUID venueId);
}
