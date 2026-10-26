package it.projectsrl.standpos.stock;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    List<Supplier> findAllByOrderByName();
}
