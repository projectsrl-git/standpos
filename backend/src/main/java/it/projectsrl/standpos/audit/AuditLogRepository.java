package it.projectsrl.standpos.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findAllByEntityIdOrderByCreatedAt(UUID entityId);
}
