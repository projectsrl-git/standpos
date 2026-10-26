package it.projectsrl.standpos.sales;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {

    List<OrderLine> findAllByOrderIdOrderByLineNo(UUID orderId);

    List<OrderLine> findAllByOrderIdInOrderByLineNo(Collection<UUID> orderIds);
}
