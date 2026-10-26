package it.projectsrl.standpos.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findAllByVenueIdOrderByCreatedAtDesc(UUID venueId);

    Optional<Device> findByIdAndVenueId(UUID id, UUID venueId);
}
