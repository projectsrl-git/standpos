package it.projectsrl.standpos.sales;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllByOrderId(UUID orderId);

    List<Payment> findAllByOrderIdIn(Collection<UUID> orderIds);
}
