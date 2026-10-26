package it.projectsrl.standpos.stock;

import it.projectsrl.standpos.auth.Authz;
import it.projectsrl.standpos.auth.StandposPrincipal;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.common.Ids;
import it.projectsrl.standpos.tenant.TenantContext;
import it.projectsrl.standpos.venue.Venue;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code stock_level} and {@code stock_movement}. Callers first {@link #lock} the levels of the
 * products they touch ({@code SELECT … FOR UPDATE}, ordered by product id so concurrent sales never deadlock), then
 * post movements: each one updates the level and appends to the ledger in the caller's transaction.
 *
 * <p>Plain JDBC keeps the locking explicit; every statement also filters on {@code tenant_id} (the application-level
 * line in front of RLS). See docs/adr/0007-stock-ledger-and-valuation.md.
 */
@Component
public class StockLedger {

    public record Reference(String type, UUID id) {

        public static Reference order(UUID id) {
            return new Reference("ORDER", id);
        }

        public static Reference goodsReceipt(UUID id) {
            return new Reference("GOODS_RECEIPT", id);
        }

        public static Reference stockCount(UUID id) {
            return new Reference("STOCK_COUNT", id);
        }
    }

    /** A locked stock level, kept current as movements are posted in this transaction. */
    public static final class Level {

        private final UUID productId;
        private final boolean trackStock;
        private final boolean manualSoldOut;
        private final Integer lowThreshold;
        private int onHand;
        private long valueCents;

        Level(UUID productId, int onHand, long valueCents, boolean trackStock, boolean manualSoldOut,
                Integer lowThreshold) {
            this.productId = productId;
            this.onHand = onHand;
            this.valueCents = valueCents;
            this.trackStock = trackStock;
            this.manualSoldOut = manualSoldOut;
            this.lowThreshold = lowThreshold;
        }

        public UUID productId() {
            return productId;
        }

        public int onHand() {
            return onHand;
        }

        public long valueCents() {
            return valueCents;
        }

        public boolean trackStock() {
            return trackStock;
        }
    }

    /** The levels locked by one transaction for one venue. */
    public static final class Locked {

        private final Venue venue;
        private final Map<UUID, Level> levels;

        Locked(Venue venue, Map<UUID, Level> levels) {
            this.venue = venue;
            this.levels = levels;
        }

        public Level get(UUID productId) {
            Level level = levels.get(productId);
            if (level == null) {
                throw new IllegalStateException("Stock level of " + productId + " was not locked");
            }
            return level;
        }

        public Venue venue() {
            return venue;
        }
    }

    private static final String LOCK = """
            select l.product_id, l.on_hand, l.value_cents, p.track_stock, p.sold_out, p.low_threshold
            from stock_level l
            join product p on p.tenant_id = l.tenant_id and p.id = l.product_id
            where l.tenant_id = ? and l.venue_id = ? and l.product_id = any (?)
            order by l.product_id
            for update of l""";

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public StockLedger(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /**
     * Locks (creating them at zero when missing) the stock levels of tracked products of {@code venue}. Untracked
     * products are rejected: they have no stock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Locked lock(Venue venue, Collection<UUID> productIds) {
        UUID tenantId = TenantContext.require();
        List<UUID> sorted = productIds.stream().distinct().sorted().toList();
        for (UUID productId : sorted) {
            // A concurrent transaction may create the same row: the unique key makes this wait, then do nothing.
            jdbc.update("""
                    insert into stock_level (id, tenant_id, venue_id, product_id)
                    select ?, p.tenant_id, p.venue_id, p.id from product p
                    where p.tenant_id = ? and p.venue_id = ? and p.id = ? and p.track_stock
                    on conflict (tenant_id, venue_id, product_id) do nothing""",
                    Ids.newId(), tenantId, venue.getId(), productId);
        }
        Map<UUID, Level> levels = new LinkedHashMap<>();
        jdbc.query(con -> {
            PreparedStatement statement = con.prepareStatement(LOCK);
            Array ids = con.createArrayOf("uuid", sorted.toArray());
            statement.setObject(1, tenantId);
            statement.setObject(2, venue.getId());
            statement.setArray(3, ids);
            return statement;
        }, rs -> {
            UUID productId = rs.getObject("product_id", UUID.class);
            levels.put(productId, new Level(productId, rs.getInt("on_hand"), rs.getLong("value_cents"),
                    rs.getBoolean("track_stock"), rs.getBoolean("sold_out"), (Integer) rs.getObject("low_threshold")));
        });
        List<UUID> missing = sorted.stream().filter(id -> !levels.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("stock.product_not_tracked", "Products without stock tracking")
                    .with("productIds", missing);
        }
        return new Locked(venue, levels);
    }

    /** Units come in at {@code unitCostCents}, or at the average cost when it is null. Returns the value added. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long receive(Locked locked, UUID productId, MovementType type, int quantity, Long unitCostCents,
            Reference reference, String note) {
        Level level = locked.get(productId);
        long value = Valuation.incoming(level.onHand, level.valueCents, quantity, unitCostCents);
        post(locked, level, type, quantity, value, reference, note);
        return value;
    }

    /** Units go out at their share of the stock value. Returns the value removed, as a positive number. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long remove(Locked locked, UUID productId, MovementType type, int quantity, Reference reference,
            String note) {
        Level level = locked.get(productId);
        long value = Valuation.outgoing(level.onHand, level.valueCents, quantity);
        post(locked, level, type, -quantity, -value, reference, note);
        return value;
    }

    /** A movement with an explicit value, for reversals that must give back exactly what a sale took. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postExact(Locked locked, UUID productId, MovementType type, int signedQuantity, long signedValue,
            Reference reference, String note) {
        Level level = locked.get(productId);
        long value = signedValue;
        if (level.valueCents + value < 0) {
            value = -level.valueCents; // never below zero, whatever earlier rounding did
        }
        post(locked, level, type, signedQuantity, value, reference, note);
    }

    private void post(Locked locked, Level level, MovementType type, int quantity, long value, Reference reference,
            String note) {
        UUID tenantId = TenantContext.require();
        UUID venueId = locked.venue.getId();
        level.onHand += quantity;
        level.valueCents += value;
        jdbc.update("""
                update stock_level set on_hand = ?, value_cents = ?, updated_at = now()
                where tenant_id = ? and venue_id = ? and product_id = ?""",
                level.onHand, level.valueCents, tenantId, venueId, level.productId);
        StandposPrincipal principal = Authz.principal().orElse(null);
        jdbc.update("""
                insert into stock_movement (id, tenant_id, venue_id, product_id, type, quantity, value_cents,
                    on_hand_after, reference_type, reference_id, user_id, device_id, note)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                Ids.newId(), tenantId, venueId, level.productId, type.name(), quantity, value, level.onHand,
                reference == null ? null : reference.type(), reference == null ? null : reference.id(),
                principal == null ? null : principal.userId(), principal == null ? null : principal.deviceId(),
                note);
        events.publishEvent(StockChanged.of(venueId, level.productId, Availability.of(level.trackStock, level.onHand,
                level.manualSoldOut, level.lowThreshold, locked.venue.isAllowNegativeStock())));
    }
}
