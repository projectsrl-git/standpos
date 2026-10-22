package it.projectsrl.standpos.venue;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface AreaRepository extends JpaRepository<Area, UUID> {}
