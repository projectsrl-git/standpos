package it.projectsrl.standpos.sales;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    Optional<Shift> findByDeviceIdAndClosedAtIsNull(UUID deviceId);
}
