package it.projectsrl.standpos.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The tenant of the current thread. Set from the authenticated principal by {@link TenantContextFilter}, or explicitly
 * with {@link #callAs} where the tenant is known before authentication (device enrolment, PIN login, bootstrap).
 *
 * <p>Read when a transaction begins: by Hibernate's tenant resolver and by {@link TenantAwareJpaTransactionManager},
 * which copies it into the PostgreSQL setting {@code app.tenant_id}. It must not change inside a transaction.
 */
public final class TenantContext {

    /** Sentinel used when no tenant is set: matches no row, so queries fail closed. */
    public static final UUID NONE = new UUID(0L, 0L);

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant in context");
        }
        return tenantId;
    }

    static void set(UUID tenantId) {
        if (tenantId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
    }

    static void clear() {
        CURRENT.remove();
    }

    public static <T> T callAs(UUID tenantId, Supplier<T> action) {
        UUID previous = CURRENT.get();
        set(tenantId);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    public static void runAs(UUID tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
