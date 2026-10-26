package it.projectsrl.standpos.printing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface PrinterRepository extends JpaRepository<Printer, UUID> {

    List<Printer> findAllByVenueIdOrderByName(UUID venueId);

    Optional<Printer> findByIdAndVenueId(UUID id, UUID venueId);

    List<Printer> findAllByAgentId(UUID agentId);
}
