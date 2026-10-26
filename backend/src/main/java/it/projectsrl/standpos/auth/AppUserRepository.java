package it.projectsrl.standpos.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** Sees only the users that hold a membership in the current tenant (RLS). */
@Transactional(readOnly = true)
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    List<AppUser> findAllByOrderByDisplayName();
}
