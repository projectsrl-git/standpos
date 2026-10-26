package it.projectsrl.standpos.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findAllByUserId(UUID userId);

    List<Membership> findAllByVenueId(UUID venueId);

    long countByRole(Role role);
}
