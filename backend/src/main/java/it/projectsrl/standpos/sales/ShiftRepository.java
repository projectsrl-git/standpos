package it.projectsrl.standpos.sales;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    Optional<Shift> findByDeviceIdAndClosedAtIsNull(UUID deviceId);

    /** The venue's shifts open at some point in [from, to): opened before {@code to}, not closed before {@code from}. */
    @Query("""
            select s from Shift s
            where s.venueId = :venueId and s.openedAt < :to and (s.closedAt is null or s.closedAt >= :from)
            order by s.openedAt""")
    List<Shift> findAllOverlapping(UUID venueId, Instant from, Instant to);
}
