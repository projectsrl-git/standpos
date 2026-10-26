package it.projectsrl.standpos.device;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface DeviceEnrolmentCodeRepository extends JpaRepository<DeviceEnrolmentCode, UUID> {}
