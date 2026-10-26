package it.projectsrl.standpos.sales;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID> {

    Optional<CustomerOrder> findByClientOrderId(UUID clientOrderId);

    Optional<CustomerOrder> findByIdAndVenueId(UUID id, UUID venueId);

    List<CustomerOrder> findAllByVenueIdAndBusinessDateOrderByDailyNumberDesc(UUID venueId, LocalDate businessDate);

    List<CustomerOrder> findAllByShiftId(UUID shiftId);
}
