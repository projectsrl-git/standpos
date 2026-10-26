package it.projectsrl.standpos.printing;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface PrintJobRepository extends JpaRepository<PrintJobEntity, UUID> {

    List<PrintJobEntity> findAllByPrinterIdInAndStatusInOrderByCreatedAt(Collection<UUID> printerIds,
            Collection<PrintJobEntity.Status> statuses);

    List<PrintJobEntity> findAllByOrderIdInOrderByCreatedAt(Collection<UUID> orderIds);

    List<PrintJobEntity> findAllByStatusInAndCreatedAtBefore(Collection<PrintJobEntity.Status> statuses, Instant before);
}
