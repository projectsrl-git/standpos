package it.projectsrl.standpos.tenant;

import jakarta.persistence.EntityManagerFactory;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies the tenant to every transaction: right after the JDBC transaction begins it runs
 * {@code select set_config('app.tenant_id', ?, true)} on the same connection. {@code is_local = true} scopes the
 * setting to the transaction, so a pooled connection never carries a tenant into the next one.
 *
 * <p>The value written is the tenant Hibernate bound to the session, so the {@code @TenantId} filter and RLS always
 * agree. Without a tenant the setting is the empty string, which the policies turn into NULL: zero rows.
 * See docs/adr/0002-tenant-context-per-transaction.md.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    private static final String SET_TENANT = "select set_config('app.tenant_id', ?, true)";

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        if (holder == null) {
            throw new IllegalStateException("No EntityManager bound after transaction begin");
        }
        Session session = holder.getEntityManager().unwrap(Session.class);
        Object tenant = session.getTenantIdentifierValue();
        String value = tenant == null || TenantContext.NONE.equals(tenant) ? "" : ((UUID) tenant).toString();
        session.doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
                statement.setString(1, value);
                statement.execute();
            }
        });
    }
}
