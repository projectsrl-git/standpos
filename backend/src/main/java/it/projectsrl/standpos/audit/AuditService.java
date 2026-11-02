package it.projectsrl.standpos.audit;

import it.projectsrl.standpos.auth.Authz;
import it.projectsrl.standpos.auth.StandposPrincipal;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Writes audit entries in the caller's transaction, attributed to the current user and device. */
@Service
public class AuditService {

    private final EntityManager entityManager;
    private final JsonMapper json;

    public AuditService(EntityManager entityManager, JsonMapper json) {
        this.entityManager = entityManager;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, UUID entityId, Object before, Object after) {
        StandposPrincipal principal = Authz.principal().orElse(null);
        UUID userId = principal == null ? null : principal.userId();
        UUID deviceId = principal == null ? null : principal.deviceId();
        entityManager.persist(new AuditLog(userId, deviceId, action, entityType, entityId, toJson(before), toJson(after)));
    }

    /** For changes made before anyone is logged in (sign-up): attributed to {@code userId}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAs(UUID userId, String action, String entityType, UUID entityId, Object before, Object after) {
        entityManager.persist(new AuditLog(userId, null, action, entityType, entityId, toJson(before), toJson(after)));
    }

    private String toJson(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }
}
